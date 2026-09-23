package com.gustler.backend.api.board;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

public class ModelOutOfScopeException extends ApiException {

    public ModelOutOfScopeException() {
        super(ErrorCode.MODEL_OUT_OF_SCOPE);
    }
}
