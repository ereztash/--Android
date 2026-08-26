#!/usr/bin/env python3
"""E1 - what is the information content of `he_bigrams.bin`, and how far is the format from it?

### Why
The bigram table is the largest single thing this app ships. `A2` established that external
*data* has little leverage left on the measured metrics; this asks whether external *method* --
the succinct-data-structure and quantised-LM literature -- has any on the bytes.

### The correction this measurement forced
It is easy to say "2.7 MB of the app is one table", and I did. **That is wrong.** The APK is a
zip and deflates its assets: the table is 2,697,304 B unpacked and the APK pays **1,682,421 B**
for it. Every saving below must be counted against the *deflated* figure, which roughly halves
the apparent prize -- and it kills the obvious optimisation outright, because deflate is already
removing the zero high bytes of every `u32` index.

### The format today
    u32 groupCount
    per group:  u32 firstWordIndex, u16 continuationCount
    per cont.:  u32 secondWordIndex, u8 logCount      <- 5 bytes each

The lexicon holds 355,587 words, so an index needs **19 bits**, not 32.

### What is printed
Raw, gzip and lzma sizes; the empirical entropy of the log-count byte and of the
sorted-within-group index gaps; an Elias-Fano estimate; and the group-header overhead. The
entropy figures are a **floor**, not a proposal: no encoder reaches its source entropy, and a
near-entropy format is essentially incompressible, so its raw size *is* its shipped size.
"""
import gzip, math, struct, collections, os
R="/home/user/--Android"
raw = gzip.decompress(open(f"{R}/lexicon/assets/he_bigrams.bin.gz","rb").read())
print(f"raw bytes           {len(raw):,}")
print(f"gzip -9 bytes       {len(gzip.compress(raw,9)):,}   (what the repo stores)")
try:
    import lzma; print(f"lzma bytes          {len(lzma.compress(raw)):,}")
except Exception: pass

off = 0
(ngroups,) = struct.unpack_from("<I", raw, off); off += 4
groups = []
total = 0
logcounts = collections.Counter()
for _ in range(ngroups):
    a, n = struct.unpack_from("<IH", raw, off); off += 6
    conts = []
    for _ in range(n):
        b, c = struct.unpack_from("<IB", raw, off); off += 5
        conts.append((b, c)); logcounts[c] += 1
    groups.append((a, conts)); total += n
print(f"\ngroups              {ngroups:,}")
print(f"continuations       {total:,}")
print(f"bytes per entry now {len(raw)/total:.3f}")

def H(counter):
    n = sum(counter.values())
    return -sum(v/n*math.log2(v/n) for v in counter.values())

# 1. the log-count byte
hc = H(logcounts)
print(f"\nlogCount: {len(logcounts)} distinct values, entropy {hc:.3f} bits "
      f"(stored in 8.000)")

# 2. the word index. absolute needs ceil(log2(lexicon)) bits
LEX = 355587
abs_bits = math.ceil(math.log2(LEX))
print(f"secondWordIndex: absolute needs {abs_bits} bits (stored in 32.000)")

# 3. sorted-by-index gaps within a group -> delta + entropy
gap_hist = collections.Counter()
ef_bits = 0
for a, conts in groups:
    idx = sorted(b for b, _ in conts)
    prev = -1
    for b in idx:
        gap_hist[min(b - prev, 1 << 20)] += 1
        prev = b
    # Elias-Fano lower bound for a monotone sequence of n values in [0, LEX)
    n = len(idx)
    if n:
        ef_bits += n * (math.ceil(math.log2(max(LEX/n, 2))) + 2)
hg = H(gap_hist)
print(f"index gaps (sorted within group): entropy {hg:.3f} bits/entry")
print(f"Elias-Fano estimate:              {ef_bits/total:.3f} bits/entry")

# group headers
hdr = 4 + ngroups*6
print(f"\ngroup headers       {hdr:,} bytes ({100*hdr/len(raw):.1f}% of the file)")

print("\n" + "="*72)
print("FLOORS, bytes per entry")
print("="*72)
print(f"  today                                    {len(raw)/total:8.3f}")
print(f"  gzip -9 of today                         {len(gzip.compress(raw,9))/total:8.3f}")
print(f"  19-bit index + 8-bit count, bit-packed   {(abs_bits+8)/8:8.3f}")
print(f"  gap-entropy + count-entropy              {(hg+hc)/8:8.3f}")
print(f"  Elias-Fano index + count-entropy         {(ef_bits/total+hc)/8:8.3f}")
print("="*72)
