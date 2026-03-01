print("h =", f"{ord('h'):08b}")
print("l =", f"{ord('l'):08b}")

print("s =", f"{ord('s'):08b}")
print("w =", f"{ord('w'):08b}")
print("u =", f"{ord('u'):08b}")
print("p =", f"{ord('p'):08b}")
print("p =", f"{ord('t'):08b}") # t -> ... wait

# h = 0110 1000
# l = 0110 1100 -> Wait, h->l is a flip in the 3rd bit from right (0x04)
# s = 0111 0011
# w = 0111 0111 -> Wait, s->w is a flip in the 3rd bit from right (0x04)
# 'u' remained 'u' ? (117)
