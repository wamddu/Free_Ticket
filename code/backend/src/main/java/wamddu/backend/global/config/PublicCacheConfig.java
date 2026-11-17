package wamddu.backend.global.config;

import org.springframework.core.MethodParameter;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import wamddu.backend.event.controller.EventController;
import wamddu.backend.ticket.controller.PublicTicketPriceHistoryController;

import java.time.Duration;

/**
 * Lets CloudFront keep the public event API for 2 seconds, so a crowd opening the same event page reaches the
 * server about once per 2 seconds instead of once per visitor.
 *
 * Only these anonymous GETs, whose answer does not depend on who asks; CloudFront's cache key for them has no
 * cookies or Authorization header (infra/edge/template.yaml). The header is added only when one of these controllers
 * writes its own successful body: errors are written by ApiExceptionHandler, so they keep Spring Security's no-cache.
 */
@ControllerAdvice(assignableTypes = {EventController.class, PublicTicketPriceHistoryController.class})
public class PublicCacheConfig implements ResponseBodyAdvice<Object> {
    static final CacheControl PUBLIC_SHORT = CacheControl.maxAge(Duration.ofSeconds(2)).cachePublic();

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (HttpMethod.GET.equals(request.getMethod())) {
            response.getHeaders().setCacheControl(PUBLIC_SHORT);
        }
        return body;
    }
}
