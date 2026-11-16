package dev.waypoint.gateway.listener;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;

/** Resolves which listener a request arrived on from its local port (ports may be random in tests). */
public class ListenerPorts implements ApplicationListener<WebServerInitializedEvent> {

    private volatile int publicPort = -1;
    private volatile Connector admin;
    private volatile Connector management;

    void register(Connector admin, Connector management) {
        this.admin = admin;
        this.management = management;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        publicPort = event.getWebServer().getPort();
    }

    public Listener resolve(int localPort) {
        if (localPort == publicPort) {
            return Listener.PUBLIC;
        }
        if (localPort == adminPort()) {
            return Listener.ADMIN;
        }
        if (localPort == managementPort()) {
            return Listener.MANAGEMENT;
        }
        return Listener.UNKNOWN;
    }

    public int publicPort() {
        return publicPort;
    }

    public int adminPort() {
        return admin == null ? -1 : admin.getLocalPort();
    }

    public int managementPort() {
        return management == null ? -1 : management.getLocalPort();
    }

    public boolean isOwnListenerPort(int port) {
        return port == publicPort || port == adminPort() || port == managementPort();
    }
}
