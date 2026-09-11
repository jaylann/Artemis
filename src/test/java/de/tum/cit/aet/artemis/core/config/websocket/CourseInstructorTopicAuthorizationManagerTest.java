package de.tum.cit.aet.artemis.core.config.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.messaging.access.intercept.MessageAuthorizationContext;

import de.tum.cit.aet.artemis.core.service.AuthorizationCheckService;

@ExtendWith(MockitoExtension.class)
class CourseInstructorTopicAuthorizationManagerTest {

    private static final long COURSE_ID = 42L;

    @Mock
    private AuthorizationCheckService authorizationCheckService;

    private CourseInstructorTopicAuthorizationManager authorizationManager;

    @BeforeEach
    void setUp() {
        authorizationManager = new CourseInstructorTopicAuthorizationManager(authorizationCheckService);
    }

    @Test
    void authorize_instructorOrAdministrator_grantsSubscription() {
        when(authorizationCheckService.isAtLeastInstructorInCourse("instructor", COURSE_ID)).thenReturn(true);
        when(authorizationCheckService.isAtLeastInstructorInCourse("admin", COURSE_ID)).thenReturn(true);

        assertThat(authorize(authenticated("instructor"), "42").isGranted()).isTrue();
        assertThat(authorize(authenticated("admin"), "42").isGranted()).isTrue();
    }

    @Test
    void authorize_studentOrUnrelatedUser_deniesSubscription() {
        when(authorizationCheckService.isAtLeastInstructorInCourse("student", COURSE_ID)).thenReturn(false);
        when(authorizationCheckService.isAtLeastInstructorInCourse("outsider", COURSE_ID)).thenReturn(false);

        assertThat(authorize(authenticated("student"), "42").isGranted()).isFalse();
        assertThat(authorize(authenticated("outsider"), "42").isGranted()).isFalse();
    }

    @Test
    void authorize_anonymousOrMissingAuthentication_deniesWithoutLookup() {
        Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        assertThat(authorize(anonymous, "42").isGranted()).isFalse();
        assertThat(authorize(null, "42").isGranted()).isFalse();
        verify(authorizationCheckService, never()).isAtLeastInstructorInCourse("anonymous", COURSE_ID);
    }

    @Test
    void authorize_missingMalformedOrNonPositiveCourseId_deniesWithoutLookup() {
        Authentication authentication = authenticated("instructor");

        assertThat(authorize(authentication, null).isGranted()).isFalse();
        assertThat(authorize(authentication, "not-a-number").isGranted()).isFalse();
        assertThat(authorize(authentication, "0").isGranted()).isFalse();
        assertThat(authorize(authentication, "-1").isGranted()).isFalse();
        verify(authorizationCheckService, never()).isAtLeastInstructorInCourse("instructor", COURSE_ID);
    }

    @Test
    void authorize_lookupFailure_deniesSubscription() {
        when(authorizationCheckService.isAtLeastInstructorInCourse("instructor", COURSE_ID)).thenThrow(new IllegalStateException("database unavailable"));

        assertThat(authorize(authenticated("instructor"), "42").isGranted()).isFalse();
    }

    private AuthorizationResult authorize(Authentication authentication, String courseId) {
        Message<?> message = new GenericMessage<>(new byte[0]);
        Map<String, String> variables = courseId == null ? Map.of() : Map.of("courseId", courseId);
        MessageAuthorizationContext<?> context = new MessageAuthorizationContext<>(message, variables);
        Supplier<Authentication> authenticationSupplier = () -> authentication;
        return authorizationManager.authorize(authenticationSupplier, context);
    }

    private static Authentication authenticated(String login) {
        return new TestingAuthenticationToken(login, "password", "ROLE_USER");
    }
}
