# Nothing app-specific: AndroidX and Compose ship their own consumer rules.
# Add a rule only with a comment saying which crash it prevents.
#
# Audited when the hand-over became a fully optimised, obfuscated R8 build
# (performance review PERF-1). Nothing in the app needs a keep rule:
# - no reflection (no Class.forName, getDeclared*, proxies or ServiceLoader);
# - no kotlinx.serialization: bridge messages, themes and settings are
#   encoded by hand with org.json, which is part of the platform;
# - no @JavascriptInterface: the preview bridge is a WebMessagePort that
#   carries JSON strings, so no Kotlin name is visible to the page;
# - enums persisted by name go through valueOf(), which R8 keeps, and
#   Enum.name stays the source name after renaming;
# - nothing reads a class's own name (simpleName, javaClass.name) as data.
