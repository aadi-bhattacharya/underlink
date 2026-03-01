# Let's generate the PERFECT Manchester transmit string for "20"
def b(c): return [int(x) for x in f"{ord(c):08b}"]

def m(bits):
    res = []
    for bit in bits:
        if bit == 1: res += [1, 0]
        else: res += [0, 1]
    return res

pre = [1, 0] * 8
len_2 = m(b('\x02'))
t_2 = m(b('2'))
t_0 = m(b('0'))
perfect = pre + len_2 + t_2 + t_0

def decode_old(validBinary, offset):
    j = offset
    decoded = []
    while j + 1 < len(validBinary):
        h1 = validBinary[j]
        h2 = validBinary[j+1]
        
        if h1 == 1 and h2 == 0: decoded.append(1)
        elif h1 == 0 and h2 == 1: decoded.append(0)
        else: decoded.append(h1)
        j += 2
    return decoded

print("Testing offsets on perfect:")
for offset in range(16):
    d = decode_old(perfect, offset)
    if len(d) >= 16:
        # Preamble Check
        p = 0
        for i in range(8): p = (p<<1) | d[i]
        
        # Length
        l = 0
        for i in range(8): l = (l<<1) | d[8+i]
        
        # Text
        txt = ""
        for i in range(l):
            if 16+(i+1)*8 > len(d): break
            t = 0
            for x in range(8): t = (t<<1) | d[16+i*8+x]
            txt += chr(t)
        print(f"Offset {offset}: Pre={p:02x}, Len={l}, Text='{txt}'")
