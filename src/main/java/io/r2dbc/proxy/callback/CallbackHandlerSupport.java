/*
 * Copyright 2018-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.r2dbc.proxy.callback;

import io.r2dbc.proxy.core.ConnectionInfo;
import io.r2dbc.proxy.core.MethodExecutionInfo;
import io.r2dbc.proxy.core.ProxyEventType;
import io.r2dbc.proxy.listener.ProxyExecutionListener;
import io.r2dbc.proxy.util.Assert;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.annotation.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static java.util.stream.Collectors.toSet;

/**
 * Defines methods to augment execution of proxy methods used by child classes.
 *
 * @author Tadaya Tsuyukubo
 */
abstract class CallbackHandlerSupport implements CallbackHandler {

    /**
     * Strategy to invoke the original instance(non-proxy) and retrieve result.
     */
    @FunctionalInterface
    public interface MethodInvocationStrategy {

        /**
         * Retrieve the actual result from original object.
         *
         * @param method invocation method
         * @param target invocation target instance
         * @param args   invocation arguments. {@code null} when invocation didn't take any arguments.
         * @return actual invocation result (not a proxy object)
         * @throws Throwable actual thrown exception
         */
        Object invoke(Method method, Object target, @Nullable Object[] args) throws Throwable;
    }

    protected static final MethodInvocationStrategy DEFAULT_INVOCATION_STRATEGY = (method, target, args) -> {
        // Perform reflective invocation on target instance.
        // When underlying method throws exception, "Method#invoke()" throws InvocationTargetException.
        // Since this strategy requires throwing originally thrown exception, catch-and-throw the original
        // exception.
        Object result;
        try {
            result = method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getTargetException();  // throw actual exception
        }
        return result;
    };

    private static final Set<Method> PASS_THROUGH_METHODS;

    static {
        try {
            Method objectToStringMethod = Object.class.getMethod("toString");
            PASS_THROUGH_METHODS = Arrays.stream(Object.class.getMethods())
                .filter(method -> !objectToStringMethod.equals(method))
                .collect(toSet());

        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Utility class to get duration of executions.
     */
    static class StopWatch {

        private final Clock clock;

        @Nullable
        private Instant startTime;

        StopWatch(Clock clock) {
            this.clock = clock;
        }

        public StopWatch start() {
            this.startTime = this.clock.instant();
            return this;
        }

        public Duration getElapsedDuration() {
            if (this.startTime == null) {
                return Duration.ZERO;  // when stopwatch has not started
            }
            return Duration.between(this.startTime, this.clock.instant());
        }
    }

    protected final ProxyConfig proxyConfig;

    protected MethodInvocationStrategy methodInvocationStrategy = DEFAULT_INVOCATION_STRATEGY;

    public CallbackHandlerSupport(ProxyConfig proxyConfig) {
        this.proxyConfig = Assert.requireNonNull(proxyConfig, "proxyConfig must not be null");
    }

    /**
     * Augment method invocation and call method listener.
     *
     * @param method         method to invoke on target
     * @param target         an object being invoked
     * @param args           arguments for the method. {@code null} if the method doesn't take any arguments.
     * @param listener       listener that before/after method callbacks will be called
     * @param connectionInfo current connection information. {@code null} when invoked operation is not associated to the {@link Connection}.
     * @param onMap          a callback that will be chained on "map()" right after the result of the method invocation
     * @param onComplete     a callback that will be chained as the first doOnComplete on the result of the method invocation
     * @return result of invoking the original object
     * @throws Throwable                thrown exception during the invocation
     * @throws IllegalArgumentException if {@code method} is {@code null}
     * @throws IllegalArgumentException if {@code target} is {@code null}
     * @throws IllegalArgumentException if {@code listener} is {@code null}
     */
    protected Object proceedExecution(Method method, Object target, @Nullable Object[] args,
                                      ProxyExecutionListener listener, @Nullable ConnectionInfo connectionInfo,
                                      @Nullable BiFunction<Object, MutableMethodExecutionInfo, Object> onMap,
                                      @Nullable Consumer<MethodExecutionInfo> onComplete) throws Throwable {
        Assert.requireNonNull(method, "method must not be null");
        Assert.requireNonNull(target, "target must not be null");
        Assert.requireNonNull(listener, "listener must not be null");

        if (PASS_THROUGH_METHODS.contains(method)) {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException ex) {
                throw ex.getTargetException();
            }
        }

        // special handling for toString()
        if ("toString".equals(method.getName())) {
            StringBuilder sb = new StringBuilder();
            sb.append(target.getClass().getSimpleName());   // ConnectionFactory, Connection, Batch, or Statement
            sb.append("-proxy [");
            sb.append(target.toString());
            sb.append("]");
            return sb.toString(); // differentiate toString message.
        }

        // special handling for "ProxyConfigHolder#getProxyConfig"
        if ("getProxyConfig".equals(method.getName())) {
            return this.proxyConfig;
        }


        StopWatch stopWatch = new StopWatch(this.proxyConfig.getClock());

        MutableMethodExecutionInfo executionInfo = new MutableMethodExecutionInfo();
        executionInfo.setMethod(method);
        executionInfo.setMethodArgs(args);
        executionInfo.setTarget(target);
        executionInfo.setConnectionInfo(connectionInfo);

        Class<?> returnType = method.getReturnType();

        if (Publisher.class.isAssignableFrom(returnType)) {

            Publisher<?> result = (Publisher<?>) this.methodInvocationStrategy.invoke(method, target, args);

            return Flux.from(result)
                .doFirst(() -> {
                    executionInfo.setThreadName(Thread.currentThread().getName());
                    executionInfo.setThreadId(Thread.currentThread().getId());
                    executionInfo.setProxyEventType(ProxyEventType.BEFORE_METHOD);

                    listener.beforeMethod(executionInfo);
                })
                .doOnSubscribe(s -> {
                    stopWatch.start();
                })
                .map(resultObj -> {

                    // set produced object as result
                    executionInfo.setResult(resultObj);

                    // apply a function to flux-chain right after the original publisher operations
                    if (onMap != null) {
                        return onMap.apply(resultObj, executionInfo);
                    }
                    return resultObj;
                })
                .doOnComplete(() -> {
                    // apply a consumer to flux-chain right after the original publisher operations
                    // this is the first chained doOnComplete on the result publisher
                    if (onComplete != null) {
                        onComplete.accept(executionInfo);
                    }
                })
                .doOnError(throwable -> {
                    executionInfo.setThrown(throwable);
                })
                .doFinally(signalType -> {
                    executionInfo.setExecuteDuration(stopWatch.getElapsedDuration());
                    executionInfo.setThreadName(Thread.currentThread().getName());
                    executionInfo.setThreadId(Thread.currentThread().getId());
                    executionInfo.setProxyEventType(ProxyEventType.AFTER_METHOD);

                    listener.afterMethod(executionInfo);
                });


        } else {
            // for method that generates non-publisher, execution happens when it is invoked.

            executionInfo.setThreadName(Thread.currentThread().getName());
            executionInfo.setThreadId(Thread.currentThread().getId());
            executionInfo.setProxyEventType(ProxyEventType.BEFORE_METHOD);

            listener.beforeMethod(executionInfo);

            stopWatch.start();

            Object result = null;
            Throwable thrown = null;
            try {
                result = this.methodInvocationStrategy.invoke(method, target, args);
            } catch (Throwable ex) {
                thrown = ex;  // capture the exception
                throw thrown;
            } finally {
                executionInfo.setResult(result);
                executionInfo.setThrown(thrown);
                executionInfo.setExecuteDuration(stopWatch.getElapsedDuration());
                executionInfo.setProxyEventType(ProxyEventType.AFTER_METHOD);

                listener.afterMethod(executionInfo);
            }
            return result;

        }

    }

    /**
     * Augment query execution result to hook up listener lifecycle.
     *
     * @param publisher     query invocation result publisher
     * @param executionInfo query execution context info
     * @return query invocation result flux
     * @throws IllegalArgumentException if {@code flux} is {@code null}
     * @throws IllegalArgumentException if {@code executionInfo} is {@code null}
     */
    protected Flux<? extends Result> interceptQueryExecution(Publisher<? extends Result> publisher, MutableQueryExecutionInfo executionInfo) {
        Assert.requireNonNull(publisher, "flux must not be null");
        Assert.requireNonNull(executionInfo, "executionInfo must not be null");

        ProxyFactory proxyFactory = this.proxyConfig.getProxyFactory();

        Flux<? extends Result> flux = new FluxQueryInvocation(Flux.from(publisher), executionInfo, this.proxyConfig)
            // return a publisher that returns proxy Result
            .flatMap(queryResult -> Mono.just(proxyFactory.wrapResult(queryResult, executionInfo)));

        return flux;

    }

    /**
     * Set {@link MethodInvocationStrategy} to invoke the original instance(non-proxy) and retrieve result.
     *
     * @param methodInvocationStrategy strategy for method invocation
     * @throws IllegalArgumentException if {@code methodInvocationStrategy} is {@code null}
     * @see MethodInvocationStrategy
     */
    public void setMethodInvocationStrategy(MethodInvocationStrategy methodInvocationStrategy) {
        this.methodInvocationStrategy = Assert.requireNonNull(methodInvocationStrategy, "methodInvocationStrategy must not be null");
    }

}
