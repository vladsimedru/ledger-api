package com.ledger.application.exception;

import java.util.UUID;

/** The resource named in the request (its path) doesn't exist. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String resource, UUID id) {
        super(resource + " " + id + " doesn't exist");
    }
}
