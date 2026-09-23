package io.github.sriharifortitude.camtmatch.camt;

/** The document is not a camt.053 statement this parser can read. The message says why. */
public class CamtException extends RuntimeException {
    public CamtException(String message) {
        super(message);
    }

    public CamtException(String message, Throwable cause) {
        super(message, cause);
    }
}
