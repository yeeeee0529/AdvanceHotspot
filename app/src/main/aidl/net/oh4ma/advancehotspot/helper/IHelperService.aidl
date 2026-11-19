package net.oh4ma.advancehotspot.helper;

import net.oh4ma.advancehotspot.helper.IClientWatcher;

// Shizuku UserService running as the shell user. Mirrors the HelperMain command-line interface.
interface IHelperService {
    // Shizuku calls this transaction code when it destroys the service.
    void destroy() = 16777114;

    // Runs one HelperMain command and returns its JSON response (without the result prefix).
    String execute(in String[] args) = 1;

    // Replaces any previous watcher; the watcher receives the same JSON as the watch command.
    void watch(IClientWatcher watcher) = 2;

    void stopWatching() = 3;

    // Runs a shell command and returns {"exitCode": int, "stdout": [..], "stderr": [..]}.
    String shell(String command) = 4;
}
