/*
 * Copyright 2024 Hazelcast Inc.
 *
 * Licensed under the Hazelcast Community License (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * http://hazelcast.com/hazelcast-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.hazelcast.web.spring;

import com.hazelcast.web.HazelcastHttpSession;
import com.hazelcast.web.WebFilter;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.session.HttpSessionCreatedEvent;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.web.context.support.WebApplicationContextUtils;

import java.util.Properties;
import java.util.logging.Level;

/**
 * Provides Spring aware Hazelcast based session replication by extending from
 * {@link com.hazelcast.web.WebFilter WebFilter}
 */
public class SpringAwareWebFilter extends WebFilter {

    protected volatile SessionRegistry sessionRegistry;

    public SpringAwareWebFilter() {
    }

    public SpringAwareWebFilter(Properties properties) {
        super(properties);
    }

    protected void ensureSessionRegistryInitialized(ApplicationContext appContext) {
        if (sessionRegistry == null) {
            synchronized (this) {
                if (sessionRegistry == null) {
                    sessionRegistry = appContext.getBean(SessionRegistry.class);
                }
            }
        }
    }

    @Override
    protected HazelcastHttpSession createNewSession(HazelcastRequestWrapper requestWrapper,
                                                    boolean create,
                                                    String existingSessionId) {
        HazelcastHttpSession session = super.createNewSession(requestWrapper, create, existingSessionId);
        ApplicationContext appContext =
                WebApplicationContextUtils.getWebApplicationContext(servletContext);
        if (appContext != null) {
            ensureSessionRegistryInitialized(appContext);
            if (sessionRegistry != null && session != null) {
                String originalSessionId = session.getOriginalSessionId();
                // If original session id is registered already, we don't need it.
                // So, we should remove it.
                sessionRegistry.removeSessionInformation(originalSessionId);
                // Publish event if this session is not registered
                if (!isSessionRegistered(session.getId())) {
                    /*
                     * Publish an event to notify
                     * {@link org.springframework.security.core.session.SessionRegistry} instance.
                     * So Spring knows our Hazelcast session.
                     *
                     * If session is already exist
                     *      (
                     *          possibly added by
                     *          {@link org.springframework.security.web.session.HttpSessionEventPublisher} instance
                     *          which is defined in {@code web.xml} before
                     *          {@link com.hazelcast.web.SessionListener} to
                     *          {@link org.springframework.security.core.session.SessionRegistry}
                     *      ),
                     * it will be just updated.
                     */
                    appContext.publishEvent(new HttpSessionCreatedEvent(session));

                    LOGGER.log(Level.FINEST, "Published create session event for Spring for session with id "
                            + session.getId());
                }
            }
        }
        return session;
    }

    @Override
    protected void destroySession(HazelcastHttpSession session, boolean invalidate) {
        super.destroySession(session, invalidate);
        if (invalidate) {
            ApplicationContext appContext =
                    WebApplicationContextUtils.getWebApplicationContext(servletContext);
            if (appContext != null) {
                ensureSessionRegistryInitialized(appContext);
                if (sessionRegistry != null) {
                    String originalSessionId = session.getOriginalSessionId();
                    // If original session id is registered already, we don't need it.
                    // So, we should remove it also.
                    sessionRegistry.removeSessionInformation(originalSessionId);
                    /*
                     * Publish an event to notify
                     * {@link org.springframework.security.core.session.SessionRegistry} instance.
                     * So Spring clears information about our Hazelcast session.
                     */
                    appContext.publishEvent(new HttpSessionDestroyedEvent(session));

                    LOGGER.log(Level.FINEST, "Published destroy session event for Spring for session with id "
                            + session.getId());
                }
            }
        }
    }

    private boolean isSessionRegistered(String sessionId) {
        if (sessionRegistry != null) {
            return sessionRegistry.getSessionInformation(sessionId) != null;
        } else {
            return false;
        }
    }

}
