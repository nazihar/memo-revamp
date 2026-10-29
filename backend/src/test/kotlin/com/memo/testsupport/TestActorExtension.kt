package com.memo.testsupport

import com.memo.common.context.ActorContext
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ReflectiveInvocationContext
import java.lang.reflect.Method

/**
 * Every integration-test method (and its set-up and tear-down) runs as the explicit system actor `test`, the way every
 * production entry point binds an actor (ADR-0017). Tests that check a specific actor bind their own inside the method.
 */
class TestActorExtension : InvocationInterceptor {
    private fun <T> asTestActor(invocation: InvocationInterceptor.Invocation<T>): T =
        if (ActorContext.currentOrNull() != null) invocation.proceed() else ActorContext.asSystem("test") { invocation.proceed() }

    override fun interceptTestMethod(
        invocation: InvocationInterceptor.Invocation<Void?>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) {
        asTestActor(invocation)
    }

    override fun interceptDynamicTest(
        invocation: InvocationInterceptor.Invocation<Void?>,
        invocationContext: org.junit.jupiter.api.extension.DynamicTestInvocationContext,
        extensionContext: ExtensionContext,
    ) {
        asTestActor(invocation)
    }

    override fun interceptTestTemplateMethod(
        invocation: InvocationInterceptor.Invocation<Void?>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) {
        asTestActor(invocation)
    }

    override fun interceptBeforeEachMethod(
        invocation: InvocationInterceptor.Invocation<Void?>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) {
        asTestActor(invocation)
    }

    override fun interceptAfterEachMethod(
        invocation: InvocationInterceptor.Invocation<Void?>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) {
        asTestActor(invocation)
    }

    override fun interceptBeforeAllMethod(
        invocation: InvocationInterceptor.Invocation<Void?>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) {
        asTestActor(invocation)
    }
}
