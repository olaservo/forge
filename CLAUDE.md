# Claude Code Notes for Forge

## Building Forge Distribution

### Correct Build Command
The proper way to build Forge with all executables and create the complete distribution package is:

```bash
mvn -U -B clean -P windows-linux install
```
