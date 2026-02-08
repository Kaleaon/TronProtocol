package com.tronprotocol.app.selfmod;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of code modification validation
 */
public class ValidationResult {
    private boolean isValid;
    private final List<String> errors;
    private final List<String> warnings;
    
    public ValidationResult() {
        this.isValid = false;
        this.errors = new ArrayList<>();
        this.warnings = new ArrayList<>();
    }
    
    public void addError(String error) {
        errors.add(error);
        isValid = false;
    }
    
    public void addWarning(String warning) {
        warnings.add(warning);
    }
    
    public void setValid(boolean valid) {
        this.isValid = valid;
    }
    
    public boolean isValid() {
        return isValid && errors.isEmpty();
    }
    
    public List<String> getErrors() {
        return new ArrayList<>(errors);
    }
    
    public List<String> getWarnings() {
        return new ArrayList<>(warnings);
    }
    
    @Override
    public String toString() {
        return "ValidationResult{" +
                "valid=" + isValid +
                ", errors=" + errors.size() +
                ", warnings=" + warnings.size() +
                '}';
    }
}
