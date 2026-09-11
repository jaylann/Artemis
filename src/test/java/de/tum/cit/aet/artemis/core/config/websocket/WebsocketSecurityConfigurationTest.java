package de.tum.cit.aet.artemis.core.config.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

import de.tum.cit.aet.artemis.core.service.AuthorizationCheckService;

class WebsocketSecurityConfigurationTest {

    private static final long COURSE_ID = 42L;

    private AuthorizationManager<Message<?>> authorizationManager;

    @BeforeEach
    void setUp() {
        AuthorizationCheckService authorizationCheckService = mock(AuthorizationCheckService.class);
        when(authorizationCheckService.isAtLeastInstructorInCourse("instructor", COURSE_ID)).thenReturn(true);
        when(authorizationCheckService.isAtLeastInstructorInCourse("student", COURSE_ID)).thenReturn(false);
        CourseInstructorTopicAuthorizationManager courseTopicAuthorizationManager = new CourseInstructorTopicAuthorizationManager(authorizationCheckService);
        authorizationManager = new WebsocketSecurityConfiguration().authorizationManager(new MessageMatcherDelegatingAuthorizationManager.Builder(),
                courseTopicAuthorizationManager);
    }

    @Test
    void authorize_exactCourseTopic_allowsInstructorAndDeniesStudent() {
        Message<?> message = subscription("/topic/atlas/orchestrator/42");

        assertThat(authorize(authenticated("instructor"), message)).isTrue();
        assertThat(authorize(authenticated("student"), message)).isFalse();
    }

    @Test
    void authorize_malformedCourseTopic_deniesInsteadOfFallingThrough() {
        Authentication authentication = authenticated("instructor");

        assertThat(authorize(authentication, subscription("/topic/atlas/orchestrator"))).isFalse();
        assertThat(authorize(authentication, subscription("/topic/atlas/orchestrator/not-a-number"))).isFalse();
        assertThat(authorize(authentication, subscription("/topic/atlas/orchestrator/42/details"))).isFalse();
    }

    @Test
    void authorize_unrelatedTopic_preservesAuthenticatedAccess() {
        Message<?> message = subscription("/topic/courses/42/operation-progress");

        assertThat(authorize(authenticated("student"), message)).isTrue();
        assertThat(authorize(anonymous(), message)).isFalse();
    }

    private boolean authorize(Authentication authentication, Message<?> message) {
        Supplier<Authentication> authenticationSupplier = () -> authentication;
        return authorizationManager.authorize(authenticationSupplier, message).isGranted();
    }

    private static Message<?> subscription(String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.SUBSCRIBE);
        accessor.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Authentication authenticated(String login) {
        return new TestingAuthenticationToken(login, "password", "ROLE_USER");
    }

    private static Authentication anonymous() {
        return new AnonymousAuthenticationToken("key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
    }
}
