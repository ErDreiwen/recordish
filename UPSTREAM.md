# Upstream provenance

This is a Forge 1.8.9 port of **Record-able** by Minewind's Jo Eusebe.

- Upstream project: https://modrinth.com/mod/record-able
- Upstream repository: https://github.com/JoEusebe/record-able
- Authoritative parity release: `V1-0.09-modern`
- Modrinth version: `Tklmbazn`
- Modrinth file: `gHWYQl3T`
- Release page: https://modrinth.com/mod/record-able/version/Tklmbazn
- Authoritative source archive SHA-256:
  `0E9EC5B21EF39A7F1BF367D142C87E59E5C1D83132BBFA86BDFFAD968A7A5EDA`
- Reference mod JAR SHA-256:
  `B265C146C2D0FE2DCA01256FE11B02D8C5F3C9A0DF3B100BA340A7A0008B4863`
- Upstream loader/version: Fabric, Minecraft 26.2
- License: MIT; the original license is retained in `LICENSE`

The official source archive attached to that Modrinth release is the
authoritative source for UI composition and behavior. The reference mod JAR is
used only to cross-check packaged metadata and assets; this port does not claim
that decompiled bytecode is its source.

The earlier `V1-0.08-legacy` release (`5vxgtdx7`), source-upload commit
`4c375d7`, and repository head `1df91b4` document the initial fork ancestry.
They are not the current parity baseline.

Minecraft 1.8.9-specific replacements are documented in
`docs/PARITY.md`. Android/Pojav-only behavior is not applicable to this
desktop Forge runtime, while desktop equivalents remain in scope.
