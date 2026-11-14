# LedgerCore Agent Rules

1. **Honesty**: never say something worked unless you ran it and saw the result.
   Paste exact error text. Never hide or soften failures.
2. **Scope**: do only the step I ask, then stop. No extra features, no new
   dependencies, no pom.xml dependency changes without asking me first.
3. **No secrets in code or commits**. Config comes from environment variables.
   .env is git-ignored; only .env.example is committed.
4. **Money is always BIGINT minor units, never float/double**. ledger_entries rows
   are insert-only (for later phases).
5. **Never run git commit, push, reset or rebase**. Give me a suggested commit
   message instead.
6. **Never delete or disable tests to make a build pass**.
7. **Explain every file you create** in 1-2 lines in the report.
8. **If something needs me** (install, login, GUI action), say exactly what, where
   and how, under "Manual actions needed". Do not try to install system software.
9. **Spring Boot 4 / Spring Framework 7 differ from Boot 3**. Do not write
   Boot 3 style code or dependency names from memory. Check the actual
   versions in pom.xml and the official Spring Boot 4 documentation,
   and tell me when you are unsure rather than guessing.
