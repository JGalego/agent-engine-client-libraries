# Changelog

All notable changes to this package will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- `app.checkpointer()` loads checkpoints with an explicit type allowlist instead
  of LangGraph's permissive default, which imports and calls any callable named
  in stored checkpoint bytes. LangChain messages, `Interrupt`, `Command`,
  standard library values, plain dicts and `TypedDict` state round-trip as
  before. Any other pydantic model, dataclass or Enum in graph state, including
  a `response_format` model in `structured_response`, now comes back as raw
  field data, and LangGraph logs a `Blocked deserialization of <module>.<name>`
  warning. `testing.build_test_agent` uses the same serializer.
- Requires `langgraph-checkpoint>=4.1.1`.

### Added
- `App(checkpoint_allowed_types=[...])` lists the custom state types a
  checkpoint load may reconstruct, as classes or `(module, name)` pairs. `True`
  is rejected, so the permissive mode cannot be turned back on.

  ```python
  from my_agent.state import Ticket

  app = App(
      app_name="My Agent",
      checkpoint_allowed_types=[Ticket, ("my_agent.state", "Priority")],
  )
  ```

  **List your custom state types before upgrading.** A session that resumes
  without its type allowlisted writes the raw data into its next checkpoint,
  and adding the type later does not convert it back, so the session's current
  state keeps the raw data.
