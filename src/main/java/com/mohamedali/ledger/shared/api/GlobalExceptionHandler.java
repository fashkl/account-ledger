package com.mohamedali.ledger.shared.api;

import com.mohamedali.ledger.ledger.domain.exception.DomainValidationException;
import com.mohamedali.ledger.shared.exception.AccountClosedException;
import com.mohamedali.ledger.shared.exception.AccountNotFoundException;
import com.mohamedali.ledger.shared.exception.IdempotencyKeyCollisionException;
import com.mohamedali.ledger.shared.exception.InsufficientFundsException;
import com.mohamedali.ledger.shared.exception.InvalidCashMovementEventException;
import com.mohamedali.ledger.shared.exception.InvalidOrderEventException;
import com.mohamedali.ledger.shared.exception.InvalidWithdrawalStateException;
import com.mohamedali.ledger.shared.exception.OrderOwnershipMismatchException;
import com.mohamedali.ledger.shared.exception.WithdrawalNotFoundException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = exception.getBindingResult()
                .getAllErrors()
                .stream()
                .map(this::toFieldViolation)
                .toList();

        ApiError error = ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Validation failed",
                request.getRequestURI(),
                violations
        );

        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException exception,
                                                              HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = exception.getConstraintViolations()
                .stream()
                .map(v -> new ApiError.FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                .toList();

        ApiError error = ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Validation failed",
                request.getRequestURI(),
                violations
        );

        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(DomainValidationException.class)
    public ResponseEntity<ApiError> handleDomainValidation(DomainValidationException exception,
                                                           HttpServletRequest request) {
        ApiError error = ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                exception.getMessage(),
                request.getRequestURI(),
                Collections.emptyList()
        );

        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ApiError> handleAccountNotFound(AccountNotFoundException exception,
                                                           HttpServletRequest request) {
        ApiError error = ApiError.of(
                HttpStatus.NOT_FOUND.value(),
                HttpStatus.NOT_FOUND.getReasonPhrase(),
                exception.getMessage(),
                request.getRequestURI(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(AccountClosedException.class)
    public ResponseEntity<ApiError> handleAccountClosed(AccountClosedException exception,
                                                         HttpServletRequest request) {
        ApiError error = ApiError.of(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                HttpStatus.UNPROCESSABLE_ENTITY.getReasonPhrase(),
                exception.getMessage(),
                request.getRequestURI(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(error);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ApiError> handleInsufficientFunds(InsufficientFundsException exception,
                                                             HttpServletRequest request) {
        ApiError error = ApiError.of(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                HttpStatus.UNPROCESSABLE_ENTITY.getReasonPhrase(),
                exception.getMessage(),
                request.getRequestURI(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(error);
    }

    @ExceptionHandler(IdempotencyKeyCollisionException.class)
    public ResponseEntity<ApiError> handleIdempotencyCollision(IdempotencyKeyCollisionException exception,
                                                                HttpServletRequest request) {
        ApiError error = ApiError.of(
                HttpStatus.CONFLICT.value(),
                HttpStatus.CONFLICT.getReasonPhrase(),
                exception.getMessage(),
                request.getRequestURI(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(WithdrawalNotFoundException.class)
    public ResponseEntity<ApiError> handleWithdrawalNotFound(WithdrawalNotFoundException exception,
                                                              HttpServletRequest request) {
        ApiError error = ApiError.of(
                HttpStatus.NOT_FOUND.value(),
                HttpStatus.NOT_FOUND.getReasonPhrase(),
                exception.getMessage(),
                request.getRequestURI(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler({InvalidWithdrawalStateException.class, InvalidCashMovementEventException.class})
    public ResponseEntity<ApiError> handleInvalidWithdrawalState(RuntimeException exception,
                                                                   HttpServletRequest request) {
        ApiError error = ApiError.of(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                HttpStatus.UNPROCESSABLE_ENTITY.getReasonPhrase(),
                exception.getMessage(),
                request.getRequestURI(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(error);
    }

    @ExceptionHandler({InvalidOrderEventException.class, OrderOwnershipMismatchException.class})
    public ResponseEntity<ApiError> handleInvalidOrderEvent(RuntimeException exception,
                                                            HttpServletRequest request) {
        ApiError error = ApiError.of(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                HttpStatus.UNPROCESSABLE_ENTITY.getReasonPhrase(),
                exception.getMessage(),
                request.getRequestURI(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(error);
    }


    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ApiError> handleCircuitOpen(CallNotPermittedException exception,
                                                      HttpServletRequest request) {
        ApiError error = ApiError.of(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(),
                "Service temporarily unavailable, retry later",
                request.getRequestURI(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "30")
                .body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnknown(Exception exception, HttpServletRequest request) {
        ApiError error = ApiError.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "Unexpected error",
                request.getRequestURI(),
                Collections.emptyList()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    private ApiError.FieldViolation toFieldViolation(org.springframework.validation.ObjectError error) {
        if (error instanceof FieldError fieldError) {
            return new ApiError.FieldViolation(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return new ApiError.FieldViolation(error.getObjectName(), error.getDefaultMessage());
    }
}
