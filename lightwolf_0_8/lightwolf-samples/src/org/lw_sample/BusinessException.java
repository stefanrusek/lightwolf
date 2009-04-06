package org.lw_sample;

public class BusinessException extends Exception {

    private static final long serialVersionUID = 1L;

    public static BusinessException buildException(Throwable cause) throws BusinessException {
        if (cause instanceof BusinessException) {
            throw new BusinessException(cause);
        }
        throw new RuntimeException(cause);
    }

    public BusinessException(String msg) {
        super(msg);
    }

    public BusinessException(Throwable cause) {
        super(cause);
    }

}
