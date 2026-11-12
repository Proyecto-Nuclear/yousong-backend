package cue.edu.co.yousongbackend.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    @SuppressWarnings("unchecked")
    void reportsFieldErrorsAndObjectLevelErrorsAlike() throws Exception {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "request");
        result.addError(new FieldError("request", "nombre", "must not be blank"));
        result.addError(new ObjectError("request", "start must be before end"));
        MethodParameter parameter = new MethodParameter(Object.class.getMethod("toString"), -1);

        ProblemDetail problem = handler.handle(new MethodArgumentNotValidException(parameter, result)).getBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsEntry("code", "VALIDATION_ERROR");
        List<Map<String, String>> errors = (List<Map<String, String>>) problem.getProperties().get("errors");
        assertThat(errors).extracting(e -> e.get("field")).containsExactlyInAnyOrder("nombre", "request");
        assertThat(errors).extracting(e -> e.get("message")).contains("must not be blank", "start must be before end");
    }
}
