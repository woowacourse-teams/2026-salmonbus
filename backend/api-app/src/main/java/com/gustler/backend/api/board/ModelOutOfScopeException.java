package com.gustler.backend.api.board;

import com.gustler.backend.api.http.ApiException;
import com.gustler.backend.api.http.ErrorCode;

public class ModelOutOfScopeException extends ApiException {

    public ModelOutOfScopeException() {
        super(ErrorCode.MODEL_OUT_OF_SCOPE);
    }
}
