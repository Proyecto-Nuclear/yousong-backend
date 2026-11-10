package cue.edu.co.yousongbackend.shared.web;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns business errors into RFC 9457 problem details with a stable {@code code} property. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handle(ApiException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        problem.setTitle(exception.status().getReasonPhrase());
        problem.setProperty("code", exception.code());
        return ResponseEntity.status(exception.status()).body(problem);
    }
}
