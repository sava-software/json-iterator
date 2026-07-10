package systems.comodal.jsoniter;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;

import static jdk.incubator.vector.VectorSpecies.of;

/// Central selection of the byte vector species used by the vectorized scan
/// and structural-index code paths.
///
/// `SPECIES_PREFERRED` resolves to the widest shape the CPU supports:
/// 128-bit on ARM NEON, 256-bit on AVX2, 512-bit on AVX-512. All mask logic in
/// this library packs lane masks into a `long`, which caps the usable width at
/// 64 lanes (512 bits) — exactly the current `SPECIES_MAX`.
final class VectorSupport {

  static final VectorSpecies<Byte> BYTE_SPECIES = ByteVector.SPECIES_PREFERRED.length() >= 16
      ? ByteVector.SPECIES_PREFERRED
      : ByteVector.SPECIES_128; // the nibble-table classification needs at least 16 lanes
  static final int BYTE_LANES = BYTE_SPECIES.length();

  // Same vector shape as BYTE_SPECIES so byte<->short conversions and int
  // reinterpretations stay within one register.
  static final VectorSpecies<Short> SHORT_SPECIES = of(short.class, BYTE_SPECIES.vectorShape());
  static final int SHORT_LANES = SHORT_SPECIES.length();
  static final VectorSpecies<Integer> INT_SPECIES = of(int.class, BYTE_SPECIES.vectorShape());

  private VectorSupport() {
  }
}
