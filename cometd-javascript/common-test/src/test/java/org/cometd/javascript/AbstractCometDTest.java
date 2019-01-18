/*
 * Copyright (c) 2008-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cometd.javascript;

import java.io.File;
import java.net.CookieStore;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import jdk.nashorn.api.scripting.JSObject;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometDServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.HttpCookieStore;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

public abstract class AbstractCometDTest {
    @Rule
    public final TestWatcher testName = new TestWatcher() {
        @Override
        public void starting(Description description) {
            super.starting(description);
            System.err.printf("Running %s.%s()%n",
                    description.getClassName(),
                    description.getMethodName());
        }
    };
    private final CookieStore cookieStore = new HttpCookieStore() {
        @Override
        public boolean removeAll() {
            return false;
        }
    };
    private final Map<String, String> sessionStore = new HashMap<>();
    protected Server server;
    protected ServerConnector connector;
    protected ServletContextHandler context;
    protected CometDServlet cometdServlet;
    protected int metaConnectPeriod = 5000;
    protected String cometdServletPath = "/cometd";
    protected int port;
    protected String contextURL;
    protected String cometdURL;
    protected BayeuxServerImpl bayeuxServer;
    protected int expirationPeriod = 2500;
    protected JavaScript javaScript;
    private XMLHttpRequestClient xhrClient;
    private WebSocketConnector wsConnector;
    private ScheduledExecutorService executor;

    @Before
    public void initCometDServer() throws Exception {
        Map<String, String> options = new HashMap<>();
        initCometDServer(options);
    }

    protected void initCometDServer(Map<String, String> options) throws Exception {
        prepareAndStartServer(options);
        initPage();
    }

    protected void prepareAndStartServer(Map<String, String> options) throws Exception {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);

        HandlerCollection handlers = new HandlerCollection();
        server.setHandler(handlers);

        String contextPath = "/cometd";
        context = new ServletContextHandler(handlers, contextPath, ServletContextHandler.SESSIONS);

        WebSocketServerContainerInitializer.configureContext(context);

        // Setup default servlet to serve static files
        context.addServlet(DefaultServlet.class, "/");

        // Setup CometD servlet
        String cometdURLMapping = cometdServletPath + "/*";
        cometdServlet = new CometDServlet();
        ServletHolder cometdServletHolder = new ServletHolder(cometdServlet);
        for (Map.Entry<String, String> entry : options.entrySet()) {
            cometdServletHolder.setInitParameter(entry.getKey(), entry.getValue());
        }
        cometdServletHolder.setInitParameter("timeout", String.valueOf(metaConnectPeriod));
        cometdServletHolder.setInitParameter("ws.cometdURLMapping", cometdURLMapping);
        context.addServlet(cometdServletHolder, cometdURLMapping);

        customizeContext(context);

        startServer();

        contextURL = "http://localhost:" + port + contextPath;
        cometdURL = contextURL + cometdServletPath;
    }

    protected void startServer() throws Exception {
        connector.setPort(port);
        server.start();
        port = connector.getLocalPort();
        bayeuxServer = cometdServlet.getBayeux();
    }

    @After
    public void destroyCometDServer() throws Exception {
        destroyPage();
        stopServer();
        cookieStore.removeAll();
    }

    protected void stopServer() throws Exception {
        server.stop();
        server.join();
    }

    protected String getLogLevel() {
        String property = Log.getLogger("org.cometd.javascript").isDebugEnabled() ? "debug" : "info";
        return property.toLowerCase(Locale.ENGLISH);
    }

    protected void customizeContext(ServletContextHandler context) throws Exception {
        File baseDirectory = new File(System.getProperty("basedir", "."));
        File overlaidScriptDirectory = new File(baseDirectory, "target/test-classes");
        File mainResourcesDirectory = new File(baseDirectory, "src/main/resources");
        File testResourcesDirectory = new File(baseDirectory, "src/test/resources");
        context.setBaseResource(new ResourceCollection(new String[]{
                overlaidScriptDirectory.getCanonicalPath(),
                mainResourcesDirectory.getCanonicalPath(),
                testResourcesDirectory.getCanonicalPath()
        }));
    }

    protected void initPage() throws Exception {
        initJavaScript();
        provideCometD();
    }

    protected void initJavaScript() throws Exception {
        javaScript = new JavaScript();
        javaScript.init();

        executor = Executors.newSingleThreadScheduledExecutor(Executors.privilegedThreadFactory());
        javaScript.putAsync("scheduler", executor);

        javaScript.evaluate(getClass().getResource("/browser.js"));
        ((JSObject)javaScript.getAsync("window")).setMember("location", contextURL);

        JavaScriptCookieStore cookies = new JavaScriptCookieStore(cookieStore);
        javaScript.putAsync("cookies", cookies);

        xhrClient = new XMLHttpRequestClient(cookies);
        xhrClient.start();
        javaScript.putAsync("xhrClient", xhrClient);

        wsConnector = new WebSocketConnector(xhrClient);
        wsConnector.start();
        javaScript.putAsync("wsConnector", wsConnector);

        SessionStorage sessionStorage = new SessionStorage(sessionStore);
        javaScript.putAsync("sessionStorage", sessionStorage);
    }

    protected void provideCometD() throws Exception {
        javaScript.evaluate(getClass().getResource("/js/cometd/cometd.js"));
        evaluateScript("cometd", "" +
                "var cometdModule = org.cometd;" +
                "var cometd = new cometdModule.CometD();" +
                "var originalTransports = {};" +
                "originalTransports['websocket'] = new cometdModule.WebSocketTransport();" +
                "originalTransports['long-polling'] = new cometdModule.LongPollingTransport();" +
                "originalTransports['callback-polling'] = new cometdModule.CallbackPollingTransport();" +
                "if (window.WebSocket) {" +
                "    cometd.registerTransport('websocket', originalTransports['websocket']);" +
                "}" +
                "cometd.registerTransport('long-polling', originalTransports['long-polling']);" +
                "cometd.registerTransport('callback-polling', originalTransports['callback-polling']);" +
                "");
    }

    protected void provideTimestampExtension() throws Exception {
        javaScript.evaluate(getClass().getResource("/js/cometd/TimeStampExtension.js"));
        javaScript.evaluate("timestamp_extension", "" +
                "cometd.registerExtension('timestamp', new cometdModule.TimeStampExtension());");
    }

    protected void provideTimesyncExtension() throws Exception {
        javaScript.evaluate(getClass().getResource("/js/cometd/TimeSyncExtension.js"));
        javaScript.evaluate("timesync_extension", "" +
                "cometd.registerExtension('timesync', new cometdModule.TimeSyncExtension());");
    }

    protected void provideMessageAcknowledgeExtension() throws Exception {
        javaScript.evaluate(getClass().getResource("/js/cometd/AckExtension.js"));
        javaScript.evaluate("ack_extension", "" +
                "cometd.registerExtension('ack', new cometdModule.AckExtension());");
    }

    protected void provideReloadExtension() throws Exception {
        javaScript.evaluate(getClass().getResource("/js/cometd/ReloadExtension.js"));
        javaScript.evaluate("reload_extension", "" +
                "cometd.registerExtension('reload', new cometdModule.ReloadExtension());");
    }

    protected void provideBinaryExtension() throws Exception {
        javaScript.evaluate(getClass().getResource("/js/cometd/BinaryExtension.js"));
        javaScript.evaluate("binary_extension", "" +
                "cometd.registerExtension('binary', new cometdModule.BinaryExtension());");
    }

    protected void destroyPage() throws Exception {
        destroyJavaScript();
    }

    protected void destroyJavaScript() throws Exception {
        if (wsConnector != null) {
            wsConnector.stop();
        }
        if (xhrClient != null) {
            xhrClient.stop();
        }
        if (javaScript != null) {
            javaScript.destroy();
            javaScript = null;
        }
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    @SuppressWarnings("unchecked")
    protected <T> T evaluateScript(String script) {
        return evaluateScript(null, script);
    }

    @SuppressWarnings("unchecked")
    protected <T> T evaluateScript(String scriptName, String script) {
        return (T)javaScript.evaluate(scriptName, script);
    }

    protected void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(x);
        }
    }

    protected void disconnect() throws InterruptedException {
        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = javaScript.get("disconnectLatch");
        evaluateScript("" +
                "if (cometd.isDisconnected()) {" +
                "    disconnectLatch.countDown();" +
                "} else {" +
                "    cometd.addListener('/meta/disconnect', function() { disconnectLatch.countDown(); });" +
                "    cometd.disconnect();" +
                "}");
        Assert.assertTrue(disconnectLatch.await(5000));
        String status = evaluateScript("cometd.getStatus();");
        Assert.assertEquals("disconnected", status);
    }
}
