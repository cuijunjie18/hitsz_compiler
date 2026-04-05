# 正则文法表示
**G = (V,T,P,S)**,其中**V={S,A,B,C,D,digit,no_0_digit,,char},T={任意符号}**，P定义如下  
(约定：用digit表示数字：0,1,2,…,9; no_0_digit表示数字：1,2,…,9;用letter表示字母：A,B,…,Z,a,b,…,z,_)

标识符 | 关键字：
- S→letter A
- A→letter A|digit A|ε
  
整常数：
- S →no_0_digitB
- B → digitB | ε

运算符：
- S → C
- C → =|*|+|-|/

分界符：
- S → D
- D → (|)|,|;

# 正则表达式表示

标识符 | 关键字：
- id→letter (letter|digit)*

整常数：
- id→no_0_digit(digit|)*

运算符：
- operator→+|-|*|\

分界符：
- delimiter→;|,|(|)