
package com.qcharge.openadr.advice;

import com.qcharge.openadr.exceptions.AccessDeniedException;
import com.qcharge.openadr.exceptions.BadRequestException;
import com.qcharge.openadr.exceptions.ResourceConflictException;
import com.qcharge.openadr.exceptions.ResourceNotFoundException;
import com.qcharge.openadr.models.constants.Constants;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Objects;

@Slf4j
@ControllerAdvice(annotations = RestController.class)
@RequiredArgsConstructor
public class CommonControllerAdvice {

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class
    })
    @ResponseBody
    protected ResponseEntity<String> handleRequestValidationException(Exception ex) {
        logQchargeStackTrace(ex);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body("Invalid request.");
    }

    @ExceptionHandler(BadRequestException.class)
    @ResponseBody
    protected ResponseEntity<String> handleBadRequestException(BadRequestException ex) {

        logQchargeStackTrace(ex);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseBody
    protected ResponseEntity<String> handleAccessDeniedException(AccessDeniedException ex) {

        logQchargeStackTrace(ex);
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ex.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseBody
    protected ResponseEntity<String> handleResourceNotFoundException(ResourceNotFoundException ex) {
        logQchargeStackTrace(ex);
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ex.getMessage());
    }

    @ExceptionHandler(ResourceConflictException.class)
    @ResponseBody
    protected ResponseEntity<String> handleResourceConflictException(ResourceConflictException ex) {
        logQchargeStackTrace(ex);
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ex.getMessage());
    }

    @ExceptionHandler(NullPointerException.class)
    @ResponseBody
    protected ResponseEntity<String> handleNullPointerException(NullPointerException ex) {
        logQchargeStackTrace(ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseBody
    protected ResponseEntity<String> handleUndefinedException(Exception ex) {
        log.error(ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ex.getMessage());
    }

    private void logQchargeStackTrace(Exception ex) {
        StringBuilder stackTrace = new StringBuilder();
        stackTrace.append(ex.getMessage() ).append(Constants.BREAK_LINE);
        if (Objects.nonNull(ex.getCause())) {
            stackTrace.append(ex.getCause().getMessage()).append(Constants.BREAK_LINE);
        }

        String packageName = obtainPackageNameWitchWillBeLogged(ex);

        Arrays.stream(ex.getStackTrace())
                .filter(st -> Objects.nonNull(st) && st.getLineNumber() > 0 && st.getClassName().contains(packageName))
                .forEach(st -> stackTrace
                        .append(Constants.EIGHT_SPACES)
                        .append(Constants.ANSI_RED)
                        .append(st.getClassName())
                        .append(Constants.DOT)
                        .append(st.getMethodName())
                        .append(Constants.SPACE_BRACE)
                        .append(st.getLineNumber())
                        .append(Constants.BRACE_SPACE)
                        .append(Constants.BREAK_LINE)
                );
        log.error(stackTrace.append(Constants.ANSI_WHITE).toString());
    }

    private String obtainPackageNameWitchWillBeLogged(Exception ex) {
        String packageName;
        String exPackageName = ex.getClass().getPackageName();
        if (exPackageName.contains(Constants.DEFAULT_PACKAGE_NAME)
                || exPackageName.contains(Constants.JAKARTA_VALIDATION_PACKAGE_NAME)
                || exPackageName.contains(Constants.SPRING_PACKAGE_NAME)
                || exPackageName.contains(Constants.JAVA_LANG_PACKAGE_NAME)
        ) {
            packageName = Constants.DEFAULT_PACKAGE_NAME;
        } else {
            packageName = StringUtils.EMPTY;
        }
        return packageName;
    }

}
