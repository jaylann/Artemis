package de.tum.cit.aet.artemis.core.config.websocket;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.messaging.access.intercept.MessageAuthorizationContext;
import org.springframework.stereotype.Component;

import de.tum.cit.aet.artemis.core.service.AuthorizationCheckService;

/**
 * Authorizes subscriptions to course-scoped WebSocket topics that expose instructor-only data.
 *
 * The matching WebSocket security rule supplies the {@code courseId} path variable. Missing or
 * malformed variables, anonymous principals, and authorization lookup failures are denied so the
 * topic cannot fall back to the broader authenticated-topic rule.
 */
@Component
@Lazy
public class CourseInstructorTopicAuthorizationManager implements AuthorizationManager<MessageAuthorizationContext<?>> {

    private static final Logger log = LoggerFactory.getLogger(CourseInstructorTopicAuthorizationManager.class);

    private static final AuthorizationDecision GRANTED = new AuthorizationDecision(true);

    private static final AuthorizationDecision DENIED = new AuthorizationDecision(false);

    private final AuthorizationCheckService authorizationCheckService;

    public CourseInstructorTopicAuthorizationManager(AuthorizationCheckService authorizationCheckService) {
        this.authorizationCheckService = authorizationCheckService;
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authenticationSupplier, MessageAuthorizationContext<?> context) {
        Authentication authentication = authenticationSupplier.get();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken || authentication.getName() == null
                || authentication.getName().isBlank()) {
            return DENIED;
        }

        String courseIdValue = context.getVariables().get("courseId");
        final long courseId;
        try {
            courseId = Long.parseLong(courseIdValue);
        }
        catch (RuntimeException exception) {
            return DENIED;
        }
        if (courseId <= 0) {
            return DENIED;
        }

        try {
            return authorizationCheckService.isAtLeastInstructorInCourse(authentication.getName(), courseId) ? GRANTED : DENIED;
        }
        catch (RuntimeException exception) {
            log.warn("Could not authorize WebSocket subscription for user {} in course {}", authentication.getName(), courseId, exception);
            return DENIED;
        }
    }
}
