
package com.resq.exception;

import com.resq.vo.ErrorResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
        private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
        private static final String INTERNAL_ERROR = "服务器内部错误，请稍后重试";

        // 原有：资源不存在 -> 404
        @ExceptionHandler(ResourceNotFoundException.class)
        public ResponseEntity<ErrorResponse> handleResourceNotFound(
                        ResourceNotFoundException e) {

                ErrorResponse body = new ErrorResponse(false, e.getMessage());

                return ResponseEntity
                                .status(HttpStatus.NOT_FOUND)
                                .body(body);
        }

        @Override
        protected ResponseEntity<Object> handleMethodArgumentNotValid(
                        MethodArgumentNotValidException e, HttpHeaders headers,
                        HttpStatusCode status, WebRequest request) {
                String message = e.getBindingResult().getAllErrors().stream()
                                .findFirst().map(error -> error.getDefaultMessage()).orElse("请求参数不合法");
                return new ResponseEntity<>(new ErrorResponse(false, message), headers, status);
        }

        @Override
        protected ResponseEntity<Object> handleExceptionInternal(
                        Exception e, Object body, HttpHeaders headers,
                        HttpStatusCode status, WebRequest request) {
                String message = status.is5xxServerError() ? INTERNAL_ERROR : "请求格式或参数不合法";
                if (e instanceof ResponseStatusException responseStatus && status.is4xxClientError()
                                && responseStatus.getReason() != null) {
                        message = responseStatus.getReason();
                }
                // Preserve Spring MVC's status and headers (e.g. Allow); never expose raw parser/SQL errors.
                return super.handleExceptionInternal(e, new ErrorResponse(false, message), headers, status, request);
        }

        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(new ErrorResponse(false, "请求参数不合法"));
        }

        @ExceptionHandler(Exception.class)
        public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
                // Keep a diagnostic type/location without logging request bodies, tokens or raw SQL messages.
                StackTraceElement[] stack = e.getStackTrace();
                log.error("未处理的请求异常，type={}，location={}", e.getClass().getName(),
                                stack.length == 0 ? "unknown" : stack[0]);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body(new ErrorResponse(false, INTERNAL_ERROR));
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
