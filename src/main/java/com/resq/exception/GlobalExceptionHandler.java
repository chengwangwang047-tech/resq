
package com.resq.exception;

import com.resq.vo.ErrorResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {

        // 原有：资源不存在 -> 404
        @ExceptionHandler(ResourceNotFoundException.class)
        public ResponseEntity<ErrorResponse> handleResourceNotFound(
                        ResourceNotFoundException e) {

                ErrorResponse body = new ErrorResponse(false, e.getMessage());

                return ResponseEntity
                                .status(HttpStatus.NOT_FOUND)
                                .body(body);
        }

        // 原有：参数校验失败 -> 400
        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<ErrorResponse> handleValidation(
                        MethodArgumentNotValidException e) {

                String message = e.getBindingResult()
                                .getFieldErrors()
                                .get(0)
                                .getDefaultMessage();

                ErrorResponse body = new ErrorResponse(false, message);

                return ResponseEntity
                                .status(HttpStatus.BAD_REQUEST)
                                .body(body);
        }

        // Day14 新增：预约不存在 -> 404
        @ExceptionHandler(NoSuchElementException.class)
        public ResponseEntity<ErrorResponse> handleNotFound(
                        NoSuchElementException e) {

                ErrorResponse body = new ErrorResponse(false, "预约不存在");

                return ResponseEntity
                                .status(HttpStatus.NOT_FOUND)
                                .body(body);
        }

        // Day14 新增：无权访问其他用户预约 -> 403
        @ExceptionHandler(SecurityException.class)
        public ResponseEntity<ErrorResponse> handleForbidden(
                        SecurityException e) {

                ErrorResponse body = new ErrorResponse(false, "无权访问该预约");

                return ResponseEntity
                                .status(HttpStatus.FORBIDDEN)
                                .body(body);
        }

        // Day17：预约时隙冲突 -> HTTP 409
        @ExceptionHandler(ReservationConflictException.class)
        public ResponseEntity<ErrorResponse> handleReservationConflict(
                        ReservationConflictException e) {

                ErrorResponse body = new ErrorResponse(false, e.getMessage());

                return ResponseEntity
                                .status(HttpStatus.CONFLICT)
                                .body(body);
        }

        // Day17：预约锁等待失败 -> HTTP 503
        @ExceptionHandler(ReservationBusyException.class)
        public ResponseEntity<ErrorResponse> handleReservationBusy(
                        ReservationBusyException e) {

                ErrorResponse body = new ErrorResponse(false, e.getMessage());

                return ResponseEntity
                                .status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(body);
        }

        @ExceptionHandler(RateLimitExceededException.class)
        public ResponseEntity<ErrorResponse> handleRateLimitExceeded(
                        RateLimitExceededException e) {

                ErrorResponse body = new ErrorResponse(false, e.getMessage());

                return ResponseEntity
                                .status(HttpStatus.TOO_MANY_REQUESTS)
                                .body(body);
        }
}
