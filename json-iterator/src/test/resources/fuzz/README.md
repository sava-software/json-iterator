# Fuzz seed corpora

One directory per fuzz target (`json`, `double`, `number`, `instant`); every
regular file inside a directory is fed to that target's harness. Keep prose
out of the corpus directories — this README sits beside them because a file
placed inside one would itself become a seed.

Seeds exist for content a from-scratch mutator takes a long time to assemble:
escapes, multibyte runs, surrogate pairs, and boundary numbers (`json`);
subnormal/overflow/rounding boundaries (`double`); integer overflow edges and
quoted forms (`number`); ISO and RFC-1123 shapes (`instant`).

Promoted fuzz findings are committed as `regression-<what-it-pins>` alongside
a named regression unit test (HARDENING.md: a crash fixed without both is a
crash that can return):

- `json/regression-invalid-hex-escape` — an invalid `\u` hex digit (any
  nibble, above `f`, or multibyte) faulted `JHex` with
  `IndexOutOfBoundsException` instead of rejecting as `JsonException`
  (`TestString.test_invalid_hex_escape_digits`).
- `double/regression-space-window` — the chars source slices its token window
  as `[head - len, head)`, so a space swallowed mid-scan shifted the window
  and dropped leading digits: `"9007199254740993993 "` read as
  `7.199254740993993E15` (`TestDouble.test_space_terminates_number_token`).
- `instant/regression-rfc-no-zone` — fixed-position field reads ran past the
  end of a truncated value: a negative-length zone `String` on the zoneless
  RFC-1123 form, stale buffer chars elsewhere
  (`TestInstant.testTruncatedInstants`).

The corpora replay inside `check` via the plugin-generated
`<Harness>SeedReplayTest` classes (one per target; regenerated every build),
so committed seeds face PIT's mutants automatically and an emptied corpus
fails rather than passes.
