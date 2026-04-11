2026-04-11T12:10:08.992+08:00  INFO 3670973 --- [ragent-service] [nio-9090-exec-2] c.n.a.r.r.strategy.SmartChatController   : Smart Chat 请求: question=你好, conversationId=null, strategy=null
2026-04-11T12:10:08.996+08:00  INFO 3670973 --- [ragent-service] [nio-9090-exec-2] c.n.a.r.rag.strategy.StrategyRouter      : 路由决策: question=你好, selectedStrategy=pipeline
2026-04-11T12:10:08.996+08:00  INFO 3670973 --- [ragent-service] [nio-9090-exec-2] c.n.a.r.rag.strategy.PipelineStrategy    : Pipeline Strategy 执行: question=你好, conversationId=null
2026-04-11T12:10:09.205+08:00  INFO 3670973 --- [ragent-service] [ntry_executor_0] c.n.a.r.r.s.impl.RAGChatServiceImpl      : 开始流式对话，会话ID：2042817452307623936，任务ID：2042817452878049281
2026-04-11T12:10:12.508+08:00  INFO 3670973 --- [ragent-service] [ntry_executor_0] .n.a.r.r.c.r.MultiQuestionRewriteService : RAG用户问题查询改写+拆分：
原始问题：你好
归一化后：你好
改写结果：你好
子问题：[你好]

2026-04-11T12:10:14.377+08:00  INFO 3670973 --- [ragent-service] [sify_executor_0] c.n.a.r.r.c.i.DefaultIntentClassifier    : 当前问题：你好
意图识别树如下所示：[
{
"node": {
"id": "general-chat",
"name": "通用对话",
"description": "处理日常问候、闲聊、系统能力询问、以及不属于任何特定知识库的通用问题。包括：打招呼、询问系统有哪些功能、询问有哪些知识库、模糊或无明确意图的问题",
"level": "DOMAIN",
"examples": [
"\"你好\"",
"\"你能做什么\"",
"\"系统里有哪些知识库\"",
"\"帮我介绍一下你自己\""
],
"fullPath": "通用对话",
"kind": "SYSTEM"
},
"score": 0.95
}
]

2026-04-11T12:10:14.390+08:00  INFO 3670973 --- [ragent-service] [ntry_executor_0] c.n.a.r.r.s.impl.RAGChatServiceImpl      : 检测到 SYSTEM 意图，转交给 Agent 策略处理
2026-04-11T12:10:14.400+08:00  WARN 3670973 --- [ragent-service] [ntry_executor_0] c.n.a.r.rag.aop.ChatRateLimitAspect      : 执行流式对话失败

com.nageoffer.ai.ragent.rag.strategy.StrategyHandoffException: 策略转交请求: target=agent, reason=SYSTEM intent requires Agent strategy
at com.nageoffer.ai.ragent.rag.strategy.StrategyHandoffException.forSystemIntent(StrategyHandoffException.java:56) ~[!/:na]
at com.nageoffer.ai.ragent.rag.service.impl.RAGChatServiceImpl.streamChat(RAGChatServiceImpl.java:114) ~[!/:na]
at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103) ~[na:na]
at java.base/java.lang.reflect.Method.invoke(Method.java:580) ~[na:na]
at com.nageoffer.ai.ragent.rag.aop.ChatRateLimitAspect.invokeWithTrace(ChatRateLimitAspect.java:107) ~[!/:na]
at com.nageoffer.ai.ragent.rag.aop.ChatRateLimitAspect.lambda$limitStreamChat$0(ChatRateLimitAspect.java:73) ~[!/:na]
at com.nageoffer.ai.ragent.rag.aop.ChatQueueLimiter.runOnAcquire(ChatQueueLimiter.java:401) ~[!/:na]
at com.nageoffer.ai.ragent.rag.aop.ChatQueueLimiter.lambda$tryAcquireIfReady$5(ChatQueueLimiter.java:224) ~[!/:na]
at com.alibaba.ttl.TtlRunnable.run(TtlRunnable.java:60) ~[transmittable-thread-local-2.14.5.jar!/:na]
at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144) ~[na:na]
at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642) ~[na:na]
at java.base/java.lang.Thread.run(Thread.java:1583) ~[na:na]

2026-04-11T12:10:14.426+08:00 ERROR 3670973 --- [ragent-service] [nio-9090-exec-3] c.n.a.r.f.web.GlobalExceptionHandler     : [GET] http://101.42.96.96/api/ragent/smart/chat?question=%E4%BD%A0%E5%A5%BD

com.nageoffer.ai.ragent.rag.strategy.StrategyHandoffException: 策略转交请求: target=agent, reason=SYSTEM intent requires Agent strategy
at com.nageoffer.ai.ragent.rag.strategy.StrategyHandoffException.forSystemIntent(StrategyHandoffException.java:56) ~[!/:na]
at com.nageoffer.ai.ragent.rag.service.impl.RAGChatServiceImpl.streamChat(RAGChatServiceImpl.java:114) ~[!/:na]
at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103) ~[na:na]
at java.base/java.lang.reflect.Method.invoke(Method.java:580) ~[na:na]
at com.nageoffer.ai.ragent.rag.aop.ChatRateLimitAspect.invokeWithTrace(ChatRateLimitAspect.java:107) ~[!/:na]
at com.nageoffer.ai.ragent.rag.aop.ChatRateLimitAspect.lambda$limitStreamChat$0(ChatRateLimitAspect.java:73) ~[!/:na]
at com.nageoffer.ai.ragent.rag.aop.ChatQueueLimiter.runOnAcquire(ChatQueueLimiter.java:401) ~[!/:na]
at com.nageoffer.ai.ragent.rag.aop.ChatQueueLimiter.lambda$tryAcquireIfReady$5(ChatQueueLimiter.java:224) ~[!/:na]
at com.alibaba.ttl.TtlRunnable.run(TtlRunnable.java:60) ~[transmittable-thread-local-2.14.5.jar!/:na]
at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144) ~[na:na]
at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642) ~[na:na]
at java.base/java.lang.Thread.run(Thread.java:1583) ~[na:na]

2026-04-11T12:10:14.429+08:00  WARN 3670973 --- [ragent-service] [nio-9090-exec-3] .m.m.a.ExceptionHandlerExceptionResolver : Failure in @ExceptionHandler com.nageoffer.ai.ragent.framework.web.GlobalExceptionHandler#defaultErrorHandler(HttpServletRequest, Throwable)

org.springframework.http.converter.HttpMessageNotWritableException: No converter for [class com.nageoffer.ai.ragent.framework.convention.Result] with preset Content-Type 'text/event-stream;charset=UTF-8'
at org.springframework.web.servlet.mvc.method.annotation.AbstractMessageConverterMethodProcessor.writeWithMessageConverters(AbstractMessageConverterMethodProcessor.java:365) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor.handleReturnValue(RequestResponseBodyMethodProcessor.java:208) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.method.support.HandlerMethodReturnValueHandlerComposite.handleReturnValue(HandlerMethodReturnValueHandlerComposite.java:78) ~[spring-web-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod.invokeAndHandle(ServletInvocableHandlerMethod.java:136) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver.doResolveHandlerMethodException(ExceptionHandlerExceptionResolver.java:471) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.handler.AbstractHandlerMethodExceptionResolver.doResolveException(AbstractHandlerMethodExceptionResolver.java:73) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.handler.AbstractHandlerExceptionResolver.resolveException(AbstractHandlerExceptionResolver.java:182) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.handler.HandlerExceptionResolverComposite.resolveException(HandlerExceptionResolverComposite.java:80) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.DispatcherServlet.processHandlerException(DispatcherServlet.java:1360) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.DispatcherServlet.processDispatchResult(DispatcherServlet.java:1161) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1106) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.DispatcherServlet.doService(DispatcherServlet.java:979) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:1014) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.FrameworkServlet.doGet(FrameworkServlet.java:903) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:527) ~[jakarta.servlet-api-6.0.0.jar!/:6.0.0]
at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:885) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:614) ~[jakarta.servlet-api-6.0.0.jar!/:6.0.0]
at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:193) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:138) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.springframework.web.filter.RequestContextFilter.doFilterInternal(RequestContextFilter.java:100) ~[spring-web-6.2.12.jar!/:6.2.12]
at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:116) ~[spring-web-6.2.12.jar!/:6.2.12]
at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:162) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:138) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:101) ~[spring-web-6.2.12.jar!/:6.2.12]
at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:162) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:138) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:101) ~[spring-web-6.2.12.jar!/:6.2.12]
at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:162) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:138) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.ApplicationDispatcher.invoke(ApplicationDispatcher.java:610) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.ApplicationDispatcher.doDispatch(ApplicationDispatcher.java:538) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.ApplicationDispatcher.dispatch(ApplicationDispatcher.java:509) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.AsyncContextImpl$AsyncRunnable.run(AsyncContextImpl.java:599) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.AsyncContextImpl.doInternalDispatch(AsyncContextImpl.java:342) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.StandardWrapperValve.invoke(StandardWrapperValve.java:163) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.StandardContextValve.invoke(StandardContextValve.java:88) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.authenticator.AuthenticatorBase.invoke(AuthenticatorBase.java:482) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.StandardHostValve.invoke(StandardHostValve.java:113) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.valves.ErrorReportValve.invoke(ErrorReportValve.java:83) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.StandardEngineValve.invoke(StandardEngineValve.java:72) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.connector.CoyoteAdapter.asyncDispatch(CoyoteAdapter.java:237) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.coyote.AbstractProcessor.dispatch(AbstractProcessor.java:243) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.coyote.AbstractProcessorLight.process(AbstractProcessorLight.java:57) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.coyote.AbstractProtocol$ConnectionHandler.process(AbstractProtocol.java:903) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.tomcat.util.net.NioEndpoint$SocketProcessor.doRun(NioEndpoint.java:1774) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.tomcat.util.net.SocketProcessorBase.run(SocketProcessorBase.java:52) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.tomcat.util.threads.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:973) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.tomcat.util.threads.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:491) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.tomcat.util.threads.TaskThread$WrappingRunnable.run(TaskThread.java:63) ~[tomcat-embed-core-10.1.48.jar!/:na]
at java.base/java.lang.Thread.run(Thread.java:1583) ~[na:na]

2026-04-11T12:10:14.431+08:00 ERROR 3670973 --- [ragent-service] [nio-9090-exec-3] o.a.c.c.C.[.[.[.[dispatcherServlet]      : Servlet.service() for servlet [dispatcherServlet] threw exception

com.nageoffer.ai.ragent.rag.strategy.StrategyHandoffException: 策略转交请求: target=agent, reason=SYSTEM intent requires Agent strategy
at com.nageoffer.ai.ragent.rag.strategy.StrategyHandoffException.forSystemIntent(StrategyHandoffException.java:56) ~[!/:na]
at com.nageoffer.ai.ragent.rag.service.impl.RAGChatServiceImpl.streamChat(RAGChatServiceImpl.java:114) ~[!/:na]
at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103) ~[na:na]
at java.base/java.lang.reflect.Method.invoke(Method.java:580) ~[na:na]
at com.nageoffer.ai.ragent.rag.aop.ChatRateLimitAspect.invokeWithTrace(ChatRateLimitAspect.java:107) ~[!/:na]
at com.nageoffer.ai.ragent.rag.aop.ChatRateLimitAspect.lambda$limitStreamChat$0(ChatRateLimitAspect.java:73) ~[!/:na]
at com.nageoffer.ai.ragent.rag.aop.ChatQueueLimiter.runOnAcquire(ChatQueueLimiter.java:401) ~[!/:na]
at com.nageoffer.ai.ragent.rag.aop.ChatQueueLimiter.lambda$tryAcquireIfReady$5(ChatQueueLimiter.java:224) ~[!/:na]
at com.alibaba.ttl.TtlRunnable.run(TtlRunnable.java:60) ~[transmittable-thread-local-2.14.5.jar!/:na]
at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144) ~[na:na]
at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642) ~[na:na]
at java.base/java.lang.Thread.run(Thread.java:1583) ~[na:na]

2026-04-11T12:10:14.432+08:00 ERROR 3670973 --- [ragent-service] [nio-9090-exec-3] o.a.c.c.C.[.[.[.[dispatcherServlet]      : Servlet.service() for servlet [dispatcherServlet] in context with path [/api/ragent] threw exception [Request processing failed: com.nageoffer.ai.ragent.rag.strategy.StrategyHandoffException: 策略转交请求: target=agent, reason=SYSTEM intent requires Agent strategy] with root cause

com.nageoffer.ai.ragent.rag.strategy.StrategyHandoffException: 策略转交请求: target=agent, reason=SYSTEM intent requires Agent strategy
at com.nageoffer.ai.ragent.rag.strategy.StrategyHandoffException.forSystemIntent(StrategyHandoffException.java:56) ~[!/:na]
at com.nageoffer.ai.ragent.rag.service.impl.RAGChatServiceImpl.streamChat(RAGChatServiceImpl.java:114) ~[!/:na]
at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103) ~[na:na]
at java.base/java.lang.reflect.Method.invoke(Method.java:580) ~[na:na]
at com.nageoffer.ai.ragent.rag.aop.ChatRateLimitAspect.invokeWithTrace(ChatRateLimitAspect.java:107) ~[!/:na]
at com.nageoffer.ai.ragent.rag.aop.ChatRateLimitAspect.lambda$limitStreamChat$0(ChatRateLimitAspect.java:73) ~[!/:na]
at com.nageoffer.ai.ragent.rag.aop.ChatQueueLimiter.runOnAcquire(ChatQueueLimiter.java:401) ~[!/:na]
at com.nageoffer.ai.ragent.rag.aop.ChatQueueLimiter.lambda$tryAcquireIfReady$5(ChatQueueLimiter.java:224) ~[!/:na]
at com.alibaba.ttl.TtlRunnable.run(TtlRunnable.java:60) ~[transmittable-thread-local-2.14.5.jar!/:na]
at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144) ~[na:na]
at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642) ~[na:na]
at java.base/java.lang.Thread.run(Thread.java:1583) ~[na:na]

2026-04-11T12:10:14.444+08:00 ERROR 3670973 --- [ragent-service] [nio-9090-exec-3] c.n.a.r.f.web.GlobalExceptionHandler     : [GET] http://101.42.96.96/api/ragent/smart/chat?question=%E4%BD%A0%E5%A5%BD

org.springframework.http.converter.HttpMessageNotWritableException: No converter for [class java.util.LinkedHashMap] with preset Content-Type 'text/event-stream;charset=UTF-8'
at org.springframework.web.servlet.mvc.method.annotation.AbstractMessageConverterMethodProcessor.writeWithMessageConverters(AbstractMessageConverterMethodProcessor.java:365) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor.handleReturnValue(HttpEntityMethodProcessor.java:263) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.mvc.method.annotation.ResponseEntityReturnValueHandler.handleReturnValue(ResponseEntityReturnValueHandler.java:79) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.method.support.HandlerMethodReturnValueHandlerComposite.handleReturnValue(HandlerMethodReturnValueHandlerComposite.java:78) ~[spring-web-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod.invokeAndHandle(ServletInvocableHandlerMethod.java:136) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.invokeHandlerMethod(RequestMappingHandlerAdapter.java:991) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.handleInternal(RequestMappingHandlerAdapter.java:896) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.mvc.method.AbstractHandlerMethodAdapter.handle(AbstractHandlerMethodAdapter.java:87) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1089) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.DispatcherServlet.doService(DispatcherServlet.java:979) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:1014) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.FrameworkServlet.doGet(FrameworkServlet.java:903) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:527) ~[jakarta.servlet-api-6.0.0.jar!/:6.0.0]
at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:885) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:614) ~[jakarta.servlet-api-6.0.0.jar!/:6.0.0]
at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:193) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:138) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.springframework.web.filter.RequestContextFilter.doFilterInternal(RequestContextFilter.java:100) ~[spring-web-6.2.12.jar!/:6.2.12]
at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:116) ~[spring-web-6.2.12.jar!/:6.2.12]
at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:162) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:138) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:101) ~[spring-web-6.2.12.jar!/:6.2.12]
at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:162) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:138) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:101) ~[spring-web-6.2.12.jar!/:6.2.12]
at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:162) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:138) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.ApplicationDispatcher.invoke(ApplicationDispatcher.java:610) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.ApplicationDispatcher.doInclude(ApplicationDispatcher.java:489) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.ApplicationDispatcher.include(ApplicationDispatcher.java:437) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.StandardHostValve.custom(StandardHostValve.java:355) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.StandardHostValve.status(StandardHostValve.java:206) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.StandardHostValve.throwable(StandardHostValve.java:283) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.StandardHostValve.invoke(StandardHostValve.java:147) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.valves.ErrorReportValve.invoke(ErrorReportValve.java:83) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.StandardEngineValve.invoke(StandardEngineValve.java:72) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.connector.CoyoteAdapter.asyncDispatch(CoyoteAdapter.java:237) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.coyote.AbstractProcessor.dispatch(AbstractProcessor.java:243) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.coyote.AbstractProcessorLight.process(AbstractProcessorLight.java:57) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.coyote.AbstractProtocol$ConnectionHandler.process(AbstractProtocol.java:903) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.tomcat.util.net.NioEndpoint$SocketProcessor.doRun(NioEndpoint.java:1774) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.tomcat.util.net.SocketProcessorBase.run(SocketProcessorBase.java:52) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.tomcat.util.threads.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:973) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.tomcat.util.threads.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:491) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.tomcat.util.threads.TaskThread$WrappingRunnable.run(TaskThread.java:63) ~[tomcat-embed-core-10.1.48.jar!/:na]
at java.base/java.lang.Thread.run(Thread.java:1583) ~[na:na]

2026-04-11T12:10:14.445+08:00  WARN 3670973 --- [ragent-service] [nio-9090-exec-3] .m.m.a.ExceptionHandlerExceptionResolver : Failure in @ExceptionHandler com.nageoffer.ai.ragent.framework.web.GlobalExceptionHandler#defaultErrorHandler(HttpServletRequest, Throwable)

org.springframework.http.converter.HttpMessageNotWritableException: No converter for [class com.nageoffer.ai.ragent.framework.convention.Result] with preset Content-Type 'text/event-stream;charset=UTF-8'
at org.springframework.web.servlet.mvc.method.annotation.AbstractMessageConverterMethodProcessor.writeWithMessageConverters(AbstractMessageConverterMethodProcessor.java:365) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor.handleReturnValue(RequestResponseBodyMethodProcessor.java:208) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.method.support.HandlerMethodReturnValueHandlerComposite.handleReturnValue(HandlerMethodReturnValueHandlerComposite.java:78) ~[spring-web-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod.invokeAndHandle(ServletInvocableHandlerMethod.java:136) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver.doResolveHandlerMethodException(ExceptionHandlerExceptionResolver.java:471) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.handler.AbstractHandlerMethodExceptionResolver.doResolveException(AbstractHandlerMethodExceptionResolver.java:73) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.handler.AbstractHandlerExceptionResolver.resolveException(AbstractHandlerExceptionResolver.java:182) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.handler.HandlerExceptionResolverComposite.resolveException(HandlerExceptionResolverComposite.java:80) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.DispatcherServlet.processHandlerException(DispatcherServlet.java:1360) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.DispatcherServlet.processDispatchResult(DispatcherServlet.java:1161) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1106) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.DispatcherServlet.doService(DispatcherServlet.java:979) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:1014) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at org.springframework.web.servlet.FrameworkServlet.doGet(FrameworkServlet.java:903) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:527) ~[jakarta.servlet-api-6.0.0.jar!/:6.0.0]
at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:885) ~[spring-webmvc-6.2.12.jar!/:6.2.12]
at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:614) ~[jakarta.servlet-api-6.0.0.jar!/:6.0.0]
at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:193) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:138) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.springframework.web.filter.RequestContextFilter.doFilterInternal(RequestContextFilter.java:100) ~[spring-web-6.2.12.jar!/:6.2.12]
at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:116) ~[spring-web-6.2.12.jar!/:6.2.12]
at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:162) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:138) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:101) ~[spring-web-6.2.12.jar!/:6.2.12]
at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:162) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:138) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:101) ~[spring-web-6.2.12.jar!/:6.2.12]
at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:162) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:138) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.ApplicationDispatcher.invoke(ApplicationDispatcher.java:610) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.ApplicationDispatcher.doInclude(ApplicationDispatcher.java:489) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.ApplicationDispatcher.include(ApplicationDispatcher.java:437) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.StandardHostValve.custom(StandardHostValve.java:355) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.StandardHostValve.status(StandardHostValve.java:206) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.StandardHostValve.throwable(StandardHostValve.java:283) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.StandardHostValve.invoke(StandardHostValve.java:147) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.valves.ErrorReportValve.invoke(ErrorReportValve.java:83) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.core.StandardEngineValve.invoke(StandardEngineValve.java:72) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.catalina.connector.CoyoteAdapter.asyncDispatch(CoyoteAdapter.java:237) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.coyote.AbstractProcessor.dispatch(AbstractProcessor.java:243) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.coyote.AbstractProcessorLight.process(AbstractProcessorLight.java:57) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.coyote.AbstractProtocol$ConnectionHandler.process(AbstractProtocol.java:903) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.tomcat.util.net.NioEndpoint$SocketProcessor.doRun(NioEndpoint.java:1774) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.tomcat.util.net.SocketProcessorBase.run(SocketProcessorBase.java:52) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.tomcat.util.threads.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:973) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.tomcat.util.threads.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:491) ~[tomcat-embed-core-10.1.48.jar!/:na]
at org.apache.tomcat.util.threads.TaskThread$WrappingRunnable.run(TaskThread.java:63) ~[tomcat-embed-core-10.1.48.jar!/:na]
at java.base/java.lang.Thread.run(Thread.java:1583) ~[na:na]

2026-04-11T12:10:14.446+08:00  WARN 3670973 --- [ragent-service] [nio-9090-exec-3] .w.s.m.s.DefaultHandlerExceptionResolver : Ignoring exception, response committed already: org.springframework.http.converter.HttpMessageNotWritableException: No converter for [class java.util.LinkedHashMap] with preset Content-Type 'text/event-stream;charset=UTF-8'
2026-04-11T12:10:14.447+08:00  WARN 3670973 --- [ragent-service] [nio-9090-exec-3] .w.s.m.s.DefaultHandlerExceptionResolver : Resolved [org.springframework.http.converter.HttpMessageNotWritableException: No converter for [class java.util.LinkedHashMap] with preset Content-Type 'text/event-stream;charset=UTF-8']
