package de.tum.cit.aet.artemis.core.config.websocket;

import static de.tum.cit.aet.artemis.core.config.Constants.PROFILE_CORE;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.socket.EnableWebSocketSecurity;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

import de.tum.cit.aet.artemis.core.security.Role;

@Profile(PROFILE_CORE)
@Configuration
@EnableWebSocketSecurity
@Lazy
public class WebsocketSecurityConfiguration {

    @Bean
    AuthorizationManager<Message<?>> authorizationManager(MessageMatcherDelegatingAuthorizationManager.Builder messages,
            CourseInstructorTopicAuthorizationManager courseInstructorTopicAuthorizationManager) {
        // @formatter:off
        messages
            .nullDestMatcher().authenticated()
            .simpDestMatchers("/topic").hasAuthority(Role.ADMIN.getAuthority())
            // Automatic-orchestration summaries belong to course instructors. The exact matcher
            // exposes courseId to the authorization manager; the following catch-all prevents
            // malformed or extended paths from reaching the broader authenticated-topic rule.
            .simpSubscribeDestMatchers("/topic/atlas/orchestrator/{courseId}").access(courseInstructorTopicAuthorizationManager)
            .simpSubscribeDestMatchers("/topic/atlas/orchestrator", "/topic/atlas/orchestrator/**").denyAll()
            // matches any destination that starts with /topic/
            // (i.e. cannot send messages directly to /topic/)
            // (i.e. cannot subscribe to /topic/messages/* to get messages sent to
            // /topic/messages-user<id>)
            .simpDestMatchers("/topic/**").authenticated()
            // message types other than MESSAGE and SUBSCRIBE
            .simpTypeMatchers(SimpMessageType.MESSAGE, SimpMessageType.SUBSCRIBE).denyAll()
            // catch all
            .anyMessage().denyAll();
        return messages.build();
        // @formatter:on
    }
}
