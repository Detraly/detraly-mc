# Contributing

Thanks for helping. Bug reports, fixes and new console redaction cases are all welcome.

## Before you start

- For anything bigger than a small fix, open an issue first so we can agree on the approach.
- The plugin talks to the Detraly API, and that API is used by servers already running older versions of this
  plugin. A change to what the plugin sends (a route, a field, a message type, a limit) needs a matching change
  on the API side, so open an issue for it rather than a pull request.
- For Security problems open an issue.

## Building and checking

You need JDK 21+ and Maven 3.9+.

```
mvn package
cd sdk
java -cp "target/classes:target/test-classes" com.detraly.analytics.pulse.ConsoleRedactorCheck
java -cp "target/classes:target/test-classes" com.detraly.analytics.privacy.PrivacyCheck
(use ";" instead of ":" in the class path on Windows; the checks need the org.json jar on it too)
```

Test on a real Paper server before opening a pull request, and say in the pull request what you tested.

## Code rules

- Never log, print or put into an exception message the link token, a link code or a player IP address.
- Analytics must never stop the server: catch failures in plugin code and keep the server running.
- Do network work off the main thread.
- Do not add a dependency without discussing it first. Everything not marked `provided` is shaded into the jar
  that server owners install.

## License of contributions

By opening a pull request, you agree that your contribution is licensed under the
[GNU General Public License v3.0](LICENSE), the same as the rest of the project.
