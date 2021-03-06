/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.testsuite.arquillian;

import java.io.File;
import org.apache.commons.io.FileUtils;
import org.jboss.arquillian.container.test.api.ContainerController;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.arquillian.test.spi.event.suite.BeforeClass;
import org.jboss.logging.Logger;
import org.keycloak.testsuite.arquillian.annotation.AppServerContainer;
import org.keycloak.testsuite.arquillian.annotation.AppServerContainers;
import org.keycloak.testsuite.arquillian.containers.SelfManagedAppContainerLifecycle;
import org.wildfly.extras.creaper.core.ManagementClient;
import org.wildfly.extras.creaper.core.online.ManagementProtocol;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.OnlineOptions;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.keycloak.testsuite.arquillian.AuthServerTestEnricher.getAuthServerContextRoot;

/**
 *
 * @author tkyjovsk
 */
public class AppServerTestEnricher {

    private static final Logger log = Logger.getLogger(AppServerTestEnricher.class);

    public static final String CURRENT_APP_SERVER = System.getProperty("app.server", "undertow");

    @Inject private Instance<ContainerController> containerConrollerInstance;
    @Inject private Instance<TestContext> testContextInstance;
    private TestContext testContext;

    public static List<String> getAppServerQualifiers(Class testClass) {
        Class<?> annotatedClass = getNearestSuperclassWithAppServerAnnotation(testClass);

        if (annotatedClass == null) return null; // no @AppServerContainer annotation --> no adapter test
        
        AppServerContainer[] appServerContainers = annotatedClass.getAnnotationsByType(AppServerContainer.class);
        
        List<String> appServerQualifiers = new ArrayList<>();
        for (AppServerContainer appServerContainer : appServerContainers) {
            appServerQualifiers.add(appServerContainer.value());
        }
        return appServerQualifiers;
    }

    public static String getAppServerContextRoot() {
        return getAppServerContextRoot(0);
    }

    public static String getAppServerContextRoot(int clusterPortOffset) {
        String host = System.getProperty("app.server.host", "localhost");
        
        boolean sslRequired = Boolean.parseBoolean(System.getProperty("app.server.ssl.required"));
  
        int port = sslRequired ? parsePort("app.server.https.port") : parsePort("app.server.http.port");
        String scheme = sslRequired ? "https" : "http";

        return String.format("%s://%s:%s", scheme, host, port + clusterPortOffset);
    }
    
    private static int parsePort(String property) {
        try {
            return Integer.parseInt(System.getProperty(property));
        } catch (NumberFormatException ex) {
            throw new RuntimeException("Failed to get " + property, ex);
        }
    }

    public void updateTestContextWithAppServerInfo(@Observes(precedence = 1) BeforeClass event) {
        testContext = testContextInstance.get();

        List<String> appServerQualifiers = getAppServerQualifiers(testContext.getTestClass());
        if (appServerQualifiers == null) { // no adapter test
            log.info("\n\n" + testContext);
            return;
        } 

        String appServerQualifier = null;
        for (String qualifier : appServerQualifiers) {
            if (qualifier.contains(";")) {// cluster adapter test
                final List<String> appServers = Arrays.asList(qualifier.split("\\s*;\\s*"));
                List<ContainerInfo> appServerBackendsInfo = testContext.getSuiteContext().getContainers().stream()
                    .filter(ci -> appServers.contains(ci.getQualifier()))
                    .map(this::updateWithAppServerInfo)
                    .collect(Collectors.toList());
                testContext.setAppServerBackendsInfo(appServerBackendsInfo);
            } else {// non-cluster adapter test
                for (ContainerInfo container : testContext.getSuiteContext().getContainers()) {
                    if (container.getQualifier().equals(qualifier)) {
                        testContext.setAppServerInfo(updateWithAppServerInfo(container));
                        appServerQualifier = qualifier;
                        break;
                    }
                    //TODO add warning if there are two or more matching containers.
                }
            }
        }
        // validate app server
        if (appServerQualifier != null && testContext.getAppServerInfo() == null) {
            throw new RuntimeException(String.format("No app server container matching '%s' was activated. Check if defined and enabled in arquillian.xml.", appServerQualifier));
        }
        log.info("\n\n" + testContext);
    }

    private ContainerInfo updateWithAppServerInfo(ContainerInfo appServerInfo) {
        return updateWithAppServerInfo(appServerInfo, 0);
    }

    private ContainerInfo updateWithAppServerInfo(ContainerInfo appServerInfo, int clusterPortOffset) {
        try {

            String appServerContextRootStr = isRelative()
                    ? getAuthServerContextRoot(clusterPortOffset)
                    : getAppServerContextRoot(clusterPortOffset);

            appServerInfo.setContextRoot(new URL(appServerContextRootStr));

        } catch (MalformedURLException ex) {
            throw new IllegalArgumentException(ex);
        }
        return appServerInfo;
    }

    public static OnlineManagementClient getManagementClient() {
        try {
            return ManagementClient.online(OnlineOptions
                    .standalone()
                    .hostAndPort(System.getProperty("app.server.host", "localhost"), System.getProperty("app.server","").startsWith("eap6") ? 10199 : 10190)
                    .protocol(System.getProperty("app.server","").startsWith("eap6") ? ManagementProtocol.REMOTE : ManagementProtocol.HTTP_REMOTING)
                    .build()
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void startAppServer(@Observes(precedence = -1) BeforeClass event) throws MalformedURLException, InterruptedException, IOException {
        // if testClass implements SelfManagedAppContainerLifecycle we skip starting container and let the test to manage the lifecycle itself
        if (SelfManagedAppContainerLifecycle.class.isAssignableFrom(event.getTestClass().getJavaClass())) {
            log.debug("Skipping starting App server. Server should be started by testClass.");
            return;
        }
        if (testContext.isAdapterContainerEnabled() && !testContext.isRelativeAdapterTest()) {
            if (isJBossBased()) {
                prepareServerDir("standalone");
            }
            ContainerController controller = containerConrollerInstance.get();
            if (!controller.isStarted(testContext.getAppServerInfo().getQualifier())) {
                log.info("Starting app server: " + testContext.getAppServerInfo().getQualifier());
                controller.start(testContext.getAppServerInfo().getQualifier());
            }
        }
    }

    /**
     * Workaround for WFARQ-44. It cannot be used 'cleanServerBaseDir' property.
     * 
     * It copies deployments and configuration into $JBOSS_HOME/standalone-test from where 
     * the container is started for the test
     * 
     * @param baseDir string representing folder name, relative to app.server.home, from which the copy is made
     * @throws IOException 
     */
    public static void prepareServerDir(String baseDir) throws IOException {
        log.debug("Creating cleanServerBaseDir from: " + baseDir);
        Path path = Paths.get(System.getProperty("app.server.home"), "standalone-test");
        File targetSubdirFile = path.toFile();
        FileUtils.deleteDirectory(targetSubdirFile);
        FileUtils.forceMkdir(targetSubdirFile);
        FileUtils.copyDirectory(Paths.get(System.getProperty("app.server.home"), baseDir, "deployments").toFile(), new File(targetSubdirFile, "deployments"));
        FileUtils.copyDirectory(Paths.get(System.getProperty("app.server.home"), baseDir, "configuration").toFile(), new File(targetSubdirFile, "configuration"));
    }

    /**
     *
     * @param testClass
     * @param annotationClass
     * @return testClass or the nearest superclass of testClass annotated with
     * annotationClass
     */
    private static Class getNearestSuperclassWithAppServerAnnotation(Class<?> testClass) {
        return (testClass.isAnnotationPresent(AppServerContainer.class) || testClass.isAnnotationPresent(AppServerContainers.class)) ? testClass
                : (testClass.getSuperclass().equals(Object.class) ? null // stop recursion
                : getNearestSuperclassWithAppServerAnnotation(testClass.getSuperclass())); // continue recursion
    }

    public static boolean hasAppServerContainerAnnotation(Class testClass) {
        return getNearestSuperclassWithAppServerAnnotation(testClass) != null;
    }

    public static boolean isUndertowAppServer() {
        return CURRENT_APP_SERVER.equals("undertow");
    }

    public static boolean isRelative() {
        return CURRENT_APP_SERVER.equals("relative");
    }

    public static boolean isWildflyAppServer() {
        return CURRENT_APP_SERVER.equals("wildfly");
    }

    public static boolean isWildfly10AppServer() {
        return CURRENT_APP_SERVER.equals("wildfly10");
    }

    public static boolean isWildfly9AppServer() {
        return CURRENT_APP_SERVER.equals("wildfly9");
    }

    public static boolean isTomcatAppServer() {
        return CURRENT_APP_SERVER.equals("tomcat");
    }

    public static boolean isEAP6AppServer() {
        return CURRENT_APP_SERVER.equals("eap6");
    }

    public static boolean isEAPAppServer() {
        return CURRENT_APP_SERVER.equals("eap");
    }

    public static boolean isWASAppServer() {
        return CURRENT_APP_SERVER.equals("was");
    }

    public static boolean isWLSAppServer() {
        return CURRENT_APP_SERVER.equals("wls");
    }

    public static boolean isOSGiAppServer() {
        return CURRENT_APP_SERVER.contains("karaf") || CURRENT_APP_SERVER.contains("fuse");
    }

    private boolean isJBossBased() {
        return testContext.getAppServerInfo().isJBossBased();
    }
}
