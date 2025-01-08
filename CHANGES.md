# Summaries of visible changes between releases

### 6.3
- Use `VIOLATED_POLICY` close code in case of too many timeouts (instead of `PROTOCOL_ERROR`).

### 6.2
- Use longer salt when hashing ping data (12 bytes instead of 4).

### 6.1
- Explicitly cancel pinging tasks on `shutdown()` in case a `shutdown()` ignoring wrapper around `ScheduledExecutorService` was passed to the constructor.

### 6.0
- Improve shutdown API to mimic this of `ExecutorService` class.
