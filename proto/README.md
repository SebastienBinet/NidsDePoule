# Protocol Buffer Definitions

This directory contains the `.proto` files that define the wire format between
the Android app and the server. **This is the single source of truth** for
data formats.

## Generating Code

### Prerequisites

Install the Protocol Buffer compiler:

```bash
# macOS
brew install protobuf

# Ubuntu/Debian
sudo apt install protobuf-compiler
```

### Generate Python code (server)

```bash
cd /path/to/NidsDePoule
pip install grpcio-tools
python -m grpc_tools.protoc \
  -I proto/ \
  --python_out=server/server/proto/ \
  proto/nidsdepoule.proto
```

### Generate Kotlin code (Android)

The Android Gradle build handles this automatically via the `protobuf` plugin.
See `android/app/build.gradle.kts` for configuration.

To generate manually:

```bash
protoc \
  -I proto/ \
  --java_out=android/app/src/main/java/ \
  proto/nidsdepoule.proto
```

## Schema Evolution Rules

1. **Never change the field number** of an existing field.
2. **Never change the type** of an existing field.
3. **New fields:** Add with a new field number. Use numbers 20+ in HitPattern
   to keep common fields compact.
4. **Deprecating fields:** Rename with `deprecated_` prefix. Don't reuse the
   field number.
5. **Increment `protocol_version`** only for breaking changes. Adding new
   fields is NOT a breaking change.
