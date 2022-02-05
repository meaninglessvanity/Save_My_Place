package org.meaninglessvanity.ssh;


public interface ExecTaskCallbackHandler {

    void onFail();

    void onComplete(String completeString);
}
