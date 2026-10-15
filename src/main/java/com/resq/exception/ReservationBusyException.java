package com.resq.exception;

public class ReservationBusyException extends RuntimeException {

    public ReservationBusyException(String message) {
        super(message);
    }

    public ReservationBusyException(String message, Throwable cause) {
        super(message, cause);
    }
}