from pypdf import PdfReader

reader = PdfReader("c:/Users/varun/Downloads/Hackenza_Problem_statement_3-1.pdf")
text = ""
for page in reader.pages:
    text += page.extract_text() + "\n"

with open("c:/Users/varun/Desktop/Hackenza-underLink/underlink/out.txt", "w", encoding="utf-8") as f:
    f.write(text)
