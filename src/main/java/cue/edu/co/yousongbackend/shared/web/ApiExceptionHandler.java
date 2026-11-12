package cue.edu.co.yousongbackend.shared.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns errors into RFC 9457 problem details with a stable {@code code} property, so a client never has
 * to parse prose and a malformed request is never reported as a server error.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handle(ApiException exception) {
        return respond(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handle(MethodArgumentNotValidException exception) {
        List<Map<String, String>> errors = new ArrayList<>();
        exception.getBindingResult().getFieldErrors().forEach(error -> errors.add(
                Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage()))));
        // Rules that involve several fields (class-level constraints) have no single field: report the object.
        exception.getBindingResult().getGlobalErrors().forEach(error -> errors.add(
                Map.of("field", error.getObjectName(), "message", String.valueOf(error.getDefaultMessage()))));
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The request has invalid fields");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handle(HttpMessageNotReadableException exception) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_BODY", "The request body is missing or is not valid JSON for this operation");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handle(MethodArgumentTypeMismatchException exception) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                "The value of '" + exception.getName() + "' is not valid");
    }

    private static ResponseEntity<ProblemDetail> respond(HttpStatus status, String code, String detail) {
        return ResponseEntity.status(status).body(problem(status, code, detail));
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        return problem;
    }
}
