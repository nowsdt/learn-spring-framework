/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.reactive.result.method;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.MethodIntrospector;
import org.springframework.http.server.RequestPath;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.AbstractHandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Abstract base class for {@link HandlerMapping} implementations that define
 * a mapping between a request and a {@link HandlerMethod}.
 *
 * <p>For each registered handler method, a unique mapping is maintained with
 * subclasses defining the details of the mapping type {@code <T>}.
 *
 * @author Rossen Stoyanchev
 * @author Brian Clozel
 * @since 5.0
 * @param <T> the mapping for a {@link HandlerMethod} containing the conditions
 * needed to match the handler method to incoming request.
 */
public abstract class AbstractHandlerMethodMapping<T> extends AbstractHandlerMapping implements InitializingBean {

	/**
	 * Bean name prefix for target beans behind scoped proxies. Used to exclude those
	 * targets from handler method detection, in favor of the corresponding proxies.
	 * <p>We're not checking the autowire-candidate status here, which is how the
	 * proxy target filtering problem is being handled at the autowiring level,
	 * since autowire-candidate may have been turned to {@code false} for other
	 * reasons, while still expecting the bean to be eligible for handler methods.
	 * <p>Originally defined in {@link org.springframework.aop.scope.ScopedProxyUtils}
	 * but duplicated here to avoid a hard dependency on the spring-aop module.
	 */
	private static final String SCOPED_TARGET_NAME_PREFIX = "scopedTarget.";

	/**
	 * HandlerMethod to return on a pre-flight request match when the request
	 * mappings are more nuanced than the access control headers.
	 */
	private static final HandlerMethod PREFLIGHT_AMBIGUOUS_MATCH =
			new HandlerMethod(new PreFlightAmbiguousMatchHandler(),
					ClassUtils.getMethod(PreFlightAmbiguousMatchHandler.class, "handle"));

	private static final CorsConfiguration ALLOW_CORS_CONFIG = new CorsConfiguration();

	static {
		ALLOW_CORS_CONFIG.addAllowedOrigin("*");
		ALLOW_CORS_CONFIG.addAllowedMethod("*");
		ALLOW_CORS_CONFIG.addAllowedHeader("*");
		ALLOW_CORS_CONFIG.setAllowCredentials(true);
	}

    /**
     * Mapping ?????????
     */
	private final MappingRegistry mappingRegistry = new MappingRegistry();

	// TODO: handlerMethodMappingNamingStrategy

	/**
	 * Return a (read-only) map with all mappings and HandlerMethod's.
	 */
	public Map<T, HandlerMethod> getHandlerMethods() {
	    // ????????????
		this.mappingRegistry.acquireReadLock();
		try {
		    // TODO ??????
			return Collections.unmodifiableMap(this.mappingRegistry.getMappings());
		} finally {
		    // ????????????
			this.mappingRegistry.releaseReadLock();
		}
	}

	/**
	 * Return the internal mapping registry. Provided for testing purposes.
	 */
	MappingRegistry getMappingRegistry() {
		return this.mappingRegistry;
	}

	/**
     * ?????? Mapping
     *
	 * Register the given mapping.
	 * <p>This method may be invoked at runtime after initialization has completed.
	 * @param mapping the mapping for the handler method
	 * @param handler the handler
	 * @param method the method
	 */
	public void registerMapping(T mapping, Object handler, Method method) {
		if (logger.isTraceEnabled()) {
			logger.trace("Register \"" + mapping + "\" to " + method.toGenericString());
		}
		// ??????
		this.mappingRegistry.register(mapping, handler, method);
	}

	/**
     * ???????????? Mapping
     *
	 * Un-register the given mapping.
	 * <p>This method may be invoked at runtime after initialization has completed.
	 * @param mapping the mapping to unregister
	 */
	public void unregisterMapping(T mapping) {
		if (logger.isTraceEnabled()) {
			logger.trace("Unregister mapping \"" + mapping);
		}
		// ????????????
		this.mappingRegistry.unregister(mapping);
	}


	// Handler method detection

	/**
	 * Detects handler methods at initialization.
	 */
	@Override
	public void afterPropertiesSet() {
	    // ??????????????????????????????
		initHandlerMethods();

		// ????????????
		// Total includes detected mappings + explicit registrations via registerMapping..
		int total = this.getHandlerMethods().size();
		if ((logger.isTraceEnabled() && total == 0) || (logger.isDebugEnabled() && total > 0) ) {
			logger.debug(total + " mappings in " + formatMappingName());
		}
	}

	/**
	 * Scan beans in the ApplicationContext, detect and register handler methods.
	 * @see #isHandler(Class)
	 * @see #getMappingForMethod(Method, Class)
	 * @see #handlerMethodsInitialized(Map)
	 */
	protected void initHandlerMethods() {
	    // ???????????? Bean ??????????????????
		String[] beanNames = obtainApplicationContext().getBeanNamesForType(Object.class);

		// ?????? Bean ??????????????? Bean ?????????????????????????????????????????????????????????
		for (String beanName : beanNames) {
			if (!beanName.startsWith(SCOPED_TARGET_NAME_PREFIX)) {
			    // ?????? Bean ??????
				Class<?> beanType = null;
				try {
					beanType = obtainApplicationContext().getType(beanName);
				} catch (Throwable ex) {
					// An unresolvable bean type, probably from a lazy bean - let's ignore it.
					if (logger.isTraceEnabled()) {
						logger.trace("Could not resolve type for bean '" + beanName + "'", ex);
					}
				}
				// ?????? Bean ?????????????????????????????????????????????????????????
				if (beanType != null && isHandler(beanType)) {
					detectHandlerMethods(beanName);
				}
			}
		}

		// ???????????????????????????????????????????????????????????????????????????
		handlerMethodsInitialized(getHandlerMethods());
	}

	/**
     * ???????????????????????????
     *
	 * Look for handler methods in a handler.
	 * @param handler the bean name of a handler or a handler instance
	 */
	protected void detectHandlerMethods(final Object handler) {
	    // ?????????????????????
		Class<?> handlerType = (handler instanceof String ?
				obtainApplicationContext().getType((String) handler) : handler.getClass());

		if (handlerType != null) {
		    // ??????????????????????????????handlerType ??????????????????
			final Class<?> userType = ClassUtils.getUserClass(handlerType);
			// ??????????????????????????????
			Map<Method, T> methods = MethodIntrospector.selectMethods(userType,
					(MethodIntrospector.MetadataLookup<T>) method -> getMappingForMethod(method, userType)); // ???????????????????????????
			if (logger.isTraceEnabled()) {
				logger.trace("Mapped " + methods.size() + " handler method(s) for " + userType + ": " + methods);
			}
			// ??????????????????????????? HandlerMethod
			methods.forEach((key, mapping) -> {
				Method invocableMethod = AopUtils.selectInvocableMethod(key, userType);
				registerHandlerMethod(handler, invocableMethod, mapping);
			});
		}
	}

	/**
     * ?????? HandlerMethod
     *
	 * Register a handler method and its unique mapping. Invoked at startup for
	 * each detected handler method.
	 * @param handler the bean name of the handler or the handler instance
	 * @param method the method to register
	 * @param mapping the mapping conditions associated with the handler method
	 * @throws IllegalStateException if another method was already registered
	 * under the same mapping
	 */
	protected void registerHandlerMethod(Object handler, Method method, T mapping) {
		this.mappingRegistry.register(mapping, handler, method);
	}

	/**
     * ?????? HandlerMethod ??????
     *
	 * Create the HandlerMethod instance.
	 * @param handler either a bean name or an actual handler instance
	 * @param method the target method
	 * @return the created HandlerMethod
	 */
	protected HandlerMethod createHandlerMethod(Object handler, Method method) {
		HandlerMethod handlerMethod;
	    // ?????? handler ????????? String??? ?????????????????? Bean ??????????????? UserController ?????? @Controller ?????????????????? handler ????????? beanName ?????? `userController`
		if (handler instanceof String) {
			String beanName = (String) handler;
			handlerMethod = new HandlerMethod(beanName,
					obtainApplicationContext().getAutowireCapableBeanFactory(), method);
        // ?????? handler ????????? String ????????????????????????????????? handler ??????????????????????????????????????? HandlerMethod ??????
		} else {
			handlerMethod = new HandlerMethod(handler, method);
		}
		return handlerMethod;
	}

	/**
	 * Extract and return the CORS configuration for the mapping.
	 */
	@Nullable
	protected CorsConfiguration initCorsConfiguration(Object handler, Method method, T mapping) {
		return null;
	}

	/**
	 * Invoked after all handler methods have been detected.
	 * @param handlerMethods a read-only map with handler methods and mappings.
	 */
	protected void handlerMethodsInitialized(Map<T, HandlerMethod> handlerMethods) {
	}

	// Handler method lookup

	/**
	 * Look up a handler method for the given request.
	 * @param exchange the current exchange
	 */
	@Override
	public Mono<HandlerMethod> getHandlerInternal(ServerWebExchange exchange) {
	    // ????????????
		this.mappingRegistry.acquireReadLock();
		try {
		    // ?????? HandlerMethod ??????
			HandlerMethod handlerMethod;
			try {
				handlerMethod = lookupHandlerMethod(exchange);
			} catch (Exception ex) {
				return Mono.error(ex);
			}
			// ?????????????????? HandlerMethod ??????
			if (handlerMethod != null) {
				handlerMethod = handlerMethod.createWithResolvedBean();
			}
			// ????????? Mono ????????????
			return Mono.justOrEmpty(handlerMethod);
		} finally {
		    // ????????????
			this.mappingRegistry.releaseReadLock();
		}
	}

	/**
	 * Look up the best-matching handler method for the current request.
	 * If multiple matches are found, the best match is selected.
	 * @param exchange the current exchange
	 * @return the best-matching handler method, or {@code null} if no match
	 * @see #handleMatch
	 * @see #handleNoMatch
	 */
	@Nullable
	protected HandlerMethod lookupHandlerMethod(ServerWebExchange exchange) throws Exception {
	    // ????????????????????????????????? Mapping ?????????????????????????????????????????? Mapping ?????????????????? matches ???
		List<Match> matches = new ArrayList<>();
		addMatchingMappings(this.mappingRegistry.getMappings().keySet(), matches, exchange);

		// ?????????????????????????????????????????? Match ????????? handlerMethod ??????
		if (!matches.isEmpty()) {
		    // ?????? MatchComparator ??????????????? matches ??????
			Comparator<Match> comparator = new MatchComparator(getMappingComparator(exchange));
			matches.sort(comparator);
			// ???????????? Match ??????
			Match bestMatch = matches.get(0);
			// ?????????????????? Match ????????????????????????
			if (matches.size() > 1) {
				if (logger.isTraceEnabled()) {
					logger.trace(exchange.getLogPrefix() + matches.size() + " matching mappings: " + matches);
				}
				// TODO 1012 cors
				if (CorsUtils.isPreFlightRequest(exchange.getRequest())) {
					return PREFLIGHT_AMBIGUOUS_MATCH;
				}
				// ??????????????? Match ??????
				Match secondBestMatch = matches.get(1);
				// ?????? bestMatch ??? secondBestMatch ?????????????????????????????????????????? IllegalStateException ??????
                // ??????????????????????????????????????????????????????????????????
				if (comparator.compare(bestMatch, secondBestMatch) == 0) {
					Method m1 = bestMatch.handlerMethod.getMethod();
					Method m2 = secondBestMatch.handlerMethod.getMethod();
					RequestPath path = exchange.getRequest().getPath();
					throw new IllegalStateException(
							"Ambiguous handler methods mapped for '" + path + "': {" + m1 + ", " + m2 + "}");
				}
			}
			// ???????????? Match ??????
			handleMatch(bestMatch.mapping, bestMatch.handlerMethod, exchange);
			// ???????????? Match ????????? handlerMethod ??????
			return bestMatch.handlerMethod;
        // ????????????????????????????????????????????????
		} else {
			return handleNoMatch(this.mappingRegistry.getMappings().keySet(), exchange);
		}
	}

	private void addMatchingMappings(Collection<T> mappings, List<Match> matches, ServerWebExchange exchange) {
	    // ?????? Mapping ??????
		for (T mapping : mappings) {
		    // ????????????
			T match = getMatchingMapping(mapping, exchange);
			// ???????????????????????? Match ?????????????????? matches ???
			if (match != null) {
				matches.add(new Match(match, this.mappingRegistry.getMappings().get(mapping)));
			}
		}
	}

	/**
	 * Invoked when a matching mapping is found.
	 * @param mapping the matching mapping
	 * @param handlerMethod the matching method
	 * @param exchange the current exchange
	 */
	protected void handleMatch(T mapping, HandlerMethod handlerMethod, ServerWebExchange exchange) {
	}

	/**
	 * Invoked when no matching mapping is not found.
	 * @param mappings all registered mappings
	 * @param exchange the current exchange
	 * @return an alternative HandlerMethod or {@code null}
	 * @throws Exception provides details that can be translated into an error status code
	 */
	@Nullable
	protected HandlerMethod handleNoMatch(Set<T> mappings, ServerWebExchange exchange) throws Exception {
		return null;
	}

	@Override
	protected CorsConfiguration getCorsConfiguration(Object handler, ServerWebExchange exchange) {
		CorsConfiguration corsConfig = super.getCorsConfiguration(handler, exchange);
		if (handler instanceof HandlerMethod) {
			HandlerMethod handlerMethod = (HandlerMethod) handler;
			if (handlerMethod.equals(PREFLIGHT_AMBIGUOUS_MATCH)) {
				return ALLOW_CORS_CONFIG;
			}
			CorsConfiguration methodConfig = this.mappingRegistry.getCorsConfiguration(handlerMethod);
			corsConfig = (corsConfig != null ? corsConfig.combine(methodConfig) : methodConfig);
		}
		return corsConfig;
	}

	// Abstract template methods

	/**
	 * Whether the given type is a handler with handler methods.
	 * @param beanType the type of the bean being checked
	 * @return "true" if this a handler type, "false" otherwise.
	 */
	protected abstract boolean isHandler(Class<?> beanType);

	/**
	 * Provide the mapping for a handler method. A method for which no
	 * mapping can be provided is not a handler method.
	 * @param method the method to provide a mapping for
	 * @param handlerType the handler type, possibly a sub-type of the method's
	 * declaring class
	 * @return the mapping, or {@code null} if the method is not mapped
	 */
	@Nullable
	protected abstract T getMappingForMethod(Method method, Class<?> handlerType);

	/**
	 * Check if a mapping matches the current request and return a (potentially
	 * new) mapping with conditions relevant to the current request.
	 * @param mapping the mapping to get a match for
	 * @param exchange the current exchange
	 * @return the match, or {@code null} if the mapping doesn't match
	 */
	@Nullable
	protected abstract T getMatchingMapping(T mapping, ServerWebExchange exchange);

	/**
	 * Return a comparator for sorting matching mappings.
	 * The returned comparator should sort 'better' matches higher.
	 * @param exchange the current exchange
	 * @return the comparator (never {@code null})
	 */
	protected abstract Comparator<T> getMappingComparator(ServerWebExchange exchange);

	/**
	 * A registry that maintains all mappings to handler methods, exposing methods
	 * to perform lookups and providing concurrent access.
	 *
	 * <p>Package-private for testing purposes.
	 */
	class MappingRegistry {

        /**
         * ?????????
         *
         * KEY: Mapping
         */
		private final Map<T, MappingRegistration<T>> registry = new HashMap<>();

        /**
         * ?????????2
         *
         * KEY???Mapping
         */
		private final Map<T, HandlerMethod> mappingLookup = new LinkedHashMap<>();

        /**
         * TODO 1012 cors
         */
		private final Map<HandlerMethod, CorsConfiguration> corsLookup = new ConcurrentHashMap<>();

        /**
         * ?????????
         */
		private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();

		/**
		 * Return all mappings and handler methods. Not thread-safe.
		 * @see #acquireReadLock()
		 */
		public Map<T, HandlerMethod> getMappings() {
			return this.mappingLookup;
		}

		/**
         * TODO 1012 cors
         *
		 * Return CORS configuration. Thread-safe for concurrent use.
		 */
		public CorsConfiguration getCorsConfiguration(HandlerMethod handlerMethod) {
			HandlerMethod original = handlerMethod.getResolvedFromHandlerMethod();
			return this.corsLookup.get(original != null ? original : handlerMethod);
		}

		/**
         * ????????????
         *
		 * Acquire the read lock when using getMappings and getMappingsByUrl.
		 */
		public void acquireReadLock() {
			this.readWriteLock.readLock().lock();
		}

		/**
         * ????????????
         *
		 * Release the read lock after using getMappings and getMappingsByUrl.
		 */
		public void releaseReadLock() {
			this.readWriteLock.readLock().unlock();
		}

		public void register(T mapping, Object handler, Method method) {
		    // ????????????
			this.readWriteLock.writeLock().lock();
			try {
			    // ?????? HandlerMethod ??????
				HandlerMethod handlerMethod = createHandlerMethod(handler, method);
				// ???????????? mapping ???????????????????????? IllegalStateException ??????
				assertUniqueMethodMapping(handlerMethod, mapping);
				// ?????? mapping + HandlerMethod ??? mappingLookup ???
				this.mappingLookup.put(mapping, handlerMethod);

				// TODO 1012 cors
				CorsConfiguration corsConfig = initCorsConfiguration(handler, method, mapping);
				if (corsConfig != null) {
					this.corsLookup.put(handlerMethod, corsConfig);
				}

				// ?????? MappingRegistration ???????????? mapping + MappingRegistration ????????? registry ???
				this.registry.put(mapping, new MappingRegistration<>(mapping, handlerMethod));
			} finally {
			    // ????????????
				this.readWriteLock.writeLock().unlock();
			}
		}

		private void assertUniqueMethodMapping(HandlerMethod newHandlerMethod, T mapping) {
			HandlerMethod handlerMethod = this.mappingLookup.get(mapping);
			if (handlerMethod != null && !handlerMethod.equals(newHandlerMethod)) { // ???????????????????????????????????????
				throw new IllegalStateException(
						"Ambiguous mapping. Cannot map '" + newHandlerMethod.getBean() + "' method \n" +
								newHandlerMethod + "\nto " + mapping + ": There is already '" +
								handlerMethod.getBean() + "' bean method\n" + handlerMethod + " mapped.");
			}
		}

		public void unregister(T mapping) {
            // ????????????
			this.readWriteLock.writeLock().lock();
			try {
			    // ??? registry ?????????
                MappingRegistration<T> definition = this.registry.remove(mapping);
				if (definition == null) {
					return;
				}
				// ??? mappingLookup ?????????
				this.mappingLookup.remove(definition.getMapping());
				// ??? corsLookup ?????????
				this.corsLookup.remove(definition.getHandlerMethod());
			} finally {
			    // ????????????
				this.readWriteLock.writeLock().unlock();
			}
		}

	}

	private static class MappingRegistration<T> {

        /**
         * Mapping ??????
         */
		private final T mapping;
        /**
         * HandlerMethod ??????
         */
		private final HandlerMethod handlerMethod;

		public MappingRegistration(T mapping, HandlerMethod handlerMethod) {
			Assert.notNull(mapping, "Mapping must not be null");
			Assert.notNull(handlerMethod, "HandlerMethod must not be null");
			this.mapping = mapping;
			this.handlerMethod = handlerMethod;
		}

		public T getMapping() {
			return this.mapping;
		}

		public HandlerMethod getHandlerMethod() {
			return this.handlerMethod;
		}

	}

	/**
	 * A thin wrapper around a matched HandlerMethod and its mapping, for the purpose of
	 * comparing the best match with a comparator in the context of the current request.
	 */
	private class Match {

        /**
         * Mapping ??????
         */
		private final T mapping;
        /**
         * HandlerMethod ??????
         */
		private final HandlerMethod handlerMethod;

		public Match(T mapping, HandlerMethod handlerMethod) {
			this.mapping = mapping;
			this.handlerMethod = handlerMethod;
		}

		@Override
		public String toString() {
			return this.mapping.toString();
		}

	}

	private class MatchComparator implements Comparator<Match> {

		private final Comparator<T> comparator;

		public MatchComparator(Comparator<T> comparator) {
			this.comparator = comparator;
		}

		@Override
		public int compare(Match match1, Match match2) {
			return this.comparator.compare(match1.mapping, match2.mapping);
		}

	}

	private static class PreFlightAmbiguousMatchHandler {

		@SuppressWarnings("unused")
		public void handle() {
			throw new UnsupportedOperationException("Not implemented");
		}
	}

}
