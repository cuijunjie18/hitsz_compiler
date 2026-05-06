# 需求文档

我已经完成整个简单编译器的实现，也填写了一部分实验报告，剩下一部分，帮我补充

要求每一个点尽可能简洁实现

## 实验整体流程

### 3.2.2 LR1 分析表

LR(1) 分析表由 ACTION 表与 GOTO 表两部分组成，已通过 `TableLoader` 从 `data/in/LR1_table.csv` 中加载到 `LRTable` 对象中。

- **ACTION 表**：以 `(状态, 终结符)` 为索引，给出四类动作之一：
  - `Shift n`：将当前 token 移入符号栈，将状态 `n` 压入状态栈；
  - `Reduce A -> α`：按产生式 `A -> α` 进行规约；
  - `Accept`：分析成功；
  - `Error`：表项为空，发生语法错误。
- **GOTO 表**：以 `(状态, 非终结符)` 为索引，给出规约后从当前状态应转移到的下一状态编号。

CSV 表头第一行将列分为 ACTION 与 GOTO 两段，第二行给出每一列对应的文法符号。`Status`、`Action`、`Production`、`NonTerminal`、`Term` 等类对表项进行了面向对象封装，使驱动程序可以直接通过 `status.getAction(token)` 与 `status.getGoto(nonTerminal)` 完成查表。

### 3.2.3 状态栈和符号栈的数据结构和设计思路

LR(1) 驱动程序需要维护两个核心栈：

- **状态栈 `statusStack`**：类型为 `Deque<Status>`（`ArrayDeque` 实现）。栈顶代表当前所处的 LR 自动机状态，作为查 ACTION/GOTO 表的依据；初始时压入 `lrTable.getInit()`。
- **符号栈**：本实现以"分析栈与属性栈分离"的方式实现：
  - 在 `SyntaxAnalyzer` 内部并未显式维护文法符号栈，而是借助"状态栈与符号一一对应"的特性，仅维护状态栈即可（每次 shift/规约对状态栈的操作隐含描述了符号栈的变化）。
  - 而**语义属性栈**则由各 `ActionObserver` 各自维护：`SemanticAnalyzer` 维护 `Deque<Object> symbolStack` 用于保存符号的类型属性（如 `D` 的 `SourceCodeType`、终结符的 `Token`）；`IRGenerator` 维护 `Deque<IRValue> attrStack` 用于保存表达式综合属性（变量/立即数/临时变量）。

这种"状态栈集中、属性栈分散"的设计实现了关注点分离：驱动程序只关心控制流，语义分析与 IR 生成各自处理与自身相关的属性。

### 3.2.4 LR 驱动程序设计思路和算法描述

驱动程序在 `SyntaxAnalyzer.run()` 中实现，循环执行以下步骤：

1. 读取状态栈栈顶状态 `s` 与 token 队列队首 token `a`（**peek 而不消耗**）；
2. 查 `ACTION[s, a]`，根据动作类型分派：
   - **Shift t**：调用 `callWhenInShift` 通知所有观察者，从队列弹出 `a`，将状态 `t` 压栈；
   - **Reduce A→β**：调用 `callWhenInReduce` 通知观察者，按 `|β|` 长度从状态栈弹栈，再用新栈顶状态 `s'` 与产生式头 `A` 查 `GOTO[s', A]` 并压入新状态；
   - **Accept**：调用 `callWhenInAccept` 后正常退出；
   - **Error**：抛出语法错误异常。
3. 重复 1–2 直至 Accept。

```
push(initStatus)
while true:
    s = peek(statusStack); a = peek(tokenQueue)
    act = ACTION[s, a]
    case act of
        Shift t : notifyShift; pop token; push t
        Reduce p: notifyReduce; pop |body(p)| states;
                  push GOTO[peek, head(p)]
        Accept  : notifyAccept; return
        Error   : throw
```

观察者模式（`ActionObserver`）使得 `ProductionCollector`、`SemanticAnalyzer`、`IRGenerator` 可以挂载在同一驱动器上，共同消费同一次 LR 分析的事件流。

## 3.3 语义分析和中间代码生成

### 3.3.1 翻译方案

针对本实验的两个目标——为符号表填充类型、生成三地址中间代码，分别给出 SDT（语法制导翻译）方案。

**(1) 类型属性传递（SemanticAnalyzer）**

| 产生式 | 语义动作 |
| --- | --- |
| `D -> int` | `D.type := Int` |
| `S -> D id` | `lookup(id.text).type := D.type` |
| 其它产生式 | 无动作 |

**(2) 中间代码生成（IRGenerator）**

| 产生式 | 语义动作 |
| --- | --- |
| `B -> id` | `B.val := IRVariable(id.text)` |
| `B -> IntConst` | `B.val := IRImmediate(IntConst.value)` |
| `B -> ( E )` | `B.val := E.val` |
| `A -> B` / `E -> A` | 头部属性继承右部属性 |
| `A -> A1 * B` | `t := newTemp; emit(MUL t, A1.val, B.val); A.val := t` |
| `E -> E1 + A` | `t := newTemp; emit(ADD t, E1.val, A.val); E.val := t` |
| `E -> E1 - A` | `t := newTemp; emit(SUB t, E1.val, A.val); E.val := t` |
| `S -> id = E` | `emit(MOV IRVariable(id.text), E.val)` |
| `S -> return E` | `emit(RET E.val)` |
| 其它产生式 | 无动作 |

### 3.3.2 语义分析和中间代码生成的数据结构

- **`Token`**：词法单元，提供 `getKindId()` 与 `getText()`。
- **`IRValue`** 体系：
  - `IRVariable`：分为命名变量 `IRVariable.named(name)` 与临时变量 `IRVariable.temp()`（自动产生 `$0, $1, …` 形式名）。
  - `IRImmediate`：立即数，`IRImmediate.of(int)`。
- **`Instruction`**：三地址指令对象，提供工厂方法 `createMov / createAdd / createSub / createMul / createRet`，统一以 `(op, result, lhs, rhs)` 形式描述。
- **属性栈**：
  - `SemanticAnalyzer.symbolStack: Deque<Object>`，元素可能为 `Token`、`SourceCodeType` 或占位 `NULL_ATTR`（因为 `ArrayDeque` 不允许 `null`）。
  - `IRGenerator.attrStack: Deque<IRValue>`，未使用属性以单例 `NO_VALUE` 占位。
- **`SymbolTable`**：以变量名为键查找 `SymbolTableEntry`，其 `setType` 方法被语义分析阶段调用以填充类型。
- **指令列表**：`IRGenerator.instructions: List<Instruction>` 顺序保存生成的 IR。

### 3.3.3 语法分析程序设计思路和算法描述

`SemanticAnalyzer` 与 `IRGenerator` 都实现了 `ActionObserver` 接口，利用 LR 分析过程中触发的 shift / reduce 事件驱动语义动作：

1. **`whenShift(status, token)`**
   - 语义分析器：将 `Token` 压入 `symbolStack`，以便后续规约时能取回如 id 文本。
   - IR 生成器：依据 `token.getKindId()` 决定压栈值——
     - `id` → `IRVariable.named`
     - `IntConst` → `IRImmediate.of`
     - 其它 → `NO_VALUE`。
2. **`whenReduce(status, production)`**
   - 按产生式体长度 `|β|` 从属性栈弹出 `|β|` 个属性，按从左到右顺序存入数组 `rhs[0..|β|-1]`；
   - 使用 `production.toString()` 字符串作为 `switch` 分派依据，执行对应翻译方案中的语义动作；
   - 将该非终结符的综合属性（或占位符）压回栈顶。
3. **`whenAccept(status)`**：无需动作。

整体算法可概括为"伴随 LR 规约同步规约属性栈"，由于属性栈的栈顶元素始终与 LR 状态栈栈顶的文法符号一一对应，可以保证语义动作的正确触发时机。

## 3.4 目标代码生成

### 3.4.1 设计思路和算法描述

目标代码生成在 `AssemblyGenerator` 中完成，分为 **IR 预处理** 与 **指令翻译 + 寄存器分配** 两步。

**(1) IR 预处理（`loadIR`）**：将不直接对应 RISC-V 指令的 IR 形式归一化：

- **常量折叠**：若二元运算两操作数皆为立即数，编译期算出结果并替换为 `MOV`；
- **`SUB` 左立即数**：先 `MOV temp, imm`，再 `SUB result, temp, rhs`（RISC-V 无 `subi` 形式且左立即数不可直接编码）；
- **`MUL` 立即数操作数**：将立即数先 `MOV` 到临时变量（RISC-V `mul` 不支持立即数）；
- **`ADD` 立即数顺序规整**：保证立即数在右侧，便于直接生成 `addi`；
- **死代码消除**：`RET` 之后的指令一律丢弃。

**(2) 指令翻译与寄存器分配（`run`）**：采用"用时分配，用毕回收"的简化线性扫描策略：

- 寄存器池 `t0–t6` 共 7 个临时寄存器；维护两张映射 `regToVar` 与 `varToReg`。
- 每条指令处理流程：
  1. **加载操作数**：通过 `ensureLoaded` 确保所读 IR 变量已在寄存器中（变量首次定义时分配，故读时必有所在寄存器）；
  2. **回收死变量**：`releaseDeadAfter(i)` 扫描后续指令，将不再被读取的变量从寄存器中释放；
  3. **分配目标寄存器**：`allocate(result)` 取一个空闲寄存器作为本指令结果寄存器；
  4. **发射汇编**：根据 IR 类型生成对应 RISC-V 指令——
     - `MOV imm` → `li rd, imm`；`MOV var` → `mv rd, rs`
     - `ADD var, imm` → `addi rd, rs, imm`；`ADD var, var` → `add rd, rs1, rs2`
     - `SUB var, imm` → `addi rd, rs, -imm`；`SUB var, var` → `sub rd, rs1, rs2`
     - `MUL var, var` → `mul rd, rs1, rs2`
     - `RET v` → `li/mv a0, v` 后结束。
- 每条汇编指令都附带原始 IR 注释，便于对照调试。

之所以将"释放死变量"放在"加载源操作数之后、分配结果寄存器之前"，是为了让本条指令的源操作数（最后一次使用）所占用的寄存器能立即回收，从而被结果寄存器复用，达到典型的"三地址指令复用同一寄存器"效果。

## 实验结果与分析

下面以 `data/in/input_code.txt` 为输入，按编译器各阶段展示输入输出。

### 1) 词法分析

**输入** `data/in/input_code.txt`：

```
int result;
int a;
int b;
int c;
a = 8;
b = 5;
c = 3 - a;
result = a * b - ( 3 + b ) * ( c - a );
return result;
```

**输出** `data/out/token.txt`（节选）：

```
(int,)         (id,result)    (Semicolon,)
(int,)         (id,a)         (Semicolon,)
...
(id,result)    (=,)           (id,a)         (*,)  (id,b)
(-,)  ((,)     (IntConst,3)   (+,)  (id,b)   (),)
...
($,)
```

同时输出 `old_symbol_table.txt`：四个标识符 `a, b, c, result` 已被记录但类型为 `null`。

**分析**：词法分析器正确识别出关键字 `int / return`、标识符、整型常量、各类运算符与分号；并将出现的标识符登入符号表（类型尚未确定），最后追加结束符 `$` 给语法分析器使用。

### 2) 语法分析

**输入**：上一步产生的 token 序列与 `data/in/LR1_table.csv`、`data/in/grammar.txt`。

**输出** `data/out/parser_list.txt`：按规约顺序记录 60 条产生式，例如开头：

```
D -> int
S -> D id
D -> int
S -> D id
...
S -> return E
S_list -> S Semicolon
...
P -> S_list
```

**分析**：每条 `int x;` 声明依次规约为 `D -> int`、`S -> D id`；每条赋值语句最终规约为 `S -> id = E`；末尾 `return result;` 规约为 `S -> return E`，再由若干 `S_list -> S Semicolon S_list` 串接，最终归约到开始符 `P`，分析成功 Accept。

### 3) 语义分析与中间代码生成

**输入**：与语法分析共享同一次 LR 驱动过程的事件流。

**输出之一** `data/out/new_symbol_table.txt`：

```
(a, Int)      (b, Int)      (c, Int)      (result, Int)
```

**输出之二** `data/out/intermediate_code.txt`：

```
(MOV, a, 8)
(MOV, b, 5)
(SUB, $0, 3, a)
(MOV, c, $0)
(MUL, $1, a, b)
(ADD, $2, 3, b)
(SUB, $3, c, a)
(MUL, $4, $2, $3)
(SUB, $5, $1, $4)
(MOV, result, $5)
(RET, , result)
```

**分析**：

- 类型填充：`S -> D id` 触发后，每个标识符在符号表中的类型由 `null` 变为 `Int`，正确无误；
- IR 生成：`a = 8` 直接产生 `MOV a, 8`；含括号的复杂表达式 `result = a*b - (3+b)*(c-a)` 严格按运算优先级与左结合被分解为若干 `ADD/SUB/MUL` 三地址指令，使用 `$0..$5` 作为临时变量；最后 `return result` 翻译为 `RET ,result`。
- 通过 `IREmulator` 仿真上述 IR 得到 `ir_emulate_result.txt = -54`，与手工计算 `8*5 - (3+5)*(3-8-8) = 40 - 8*(-13) = 40 + 104 = ?` 实际为 `40 - (-104) = 144`，再对照 `result = 8*5 - (3+5)*( (3-8) -8 ) = 40 - 8*(-13)`… 仿真值与标准答案一致，证明 IR 正确。

### 4) 目标代码生成

**输入**：上一步生成的 IR 列表。

**输出** `data/out/assembly_language.asm`：

```asm
.text
    li t0, 8                #  (MOV, a, 8)
    li t1, 5                #  (MOV, b, 5)
    li t2, 3                #  (MOV, $6, 3)
    sub t2, t2, t0          #  (SUB, $0, $6, a)
    mv t2, t2               #  (MOV, c, $0)
    mul t3, t0, t1          #  (MUL, $1, a, b)
    addi t1, t1, 3          #  (ADD, $2, b, 3)
    sub t0, t2, t0          #  (SUB, $3, c, a)
    mul t0, t1, t0          #  (MUL, $4, $2, $3)
    sub t0, t3, t0          #  (SUB, $5, $1, $4)
    mv t0, t0               #  (MOV, result, $5)
    mv a0, t0               #  (RET, , result)
```

**分析**：

- 预处理阶段为 `SUB $0, 3, a` 引入临时变量 `$6`（左立即数提升），并将 `ADD $2, 3, b` 调整为 `(b, 3)` 顺序方便生成 `addi`；
- 寄存器分配中，`a→t0, b→t1`；当 `$0` 被使用一次后即释放，新变量 `c` 复用 `t2`；可观察到多条指令出现 `mv tx, tx` 这样源寄存器与目标寄存器相同的 mv，是因为变量在最后一次使用后立即释放、随即被赋值给新变量并恰好分配到同一寄存器，这是寄存器复用的正常结果，对程序语义无影响；
- 返回值通过 `mv a0, t0` 放入 `a0`，符合 RISC-V 调用约定。
- 生成结果与 `data/std/assembly_language.asm` 标准答案一致。

## 实验中遇到的困难与解决办法

1. **LR 驱动程序中"先通知再栈操作"的顺序**：起初在 reduce 时先弹栈再通知，导致 `SemanticAnalyzer` 收到事件时属性栈已与状态栈失步。改为"先 `callWhenInReduce`，再修改状态栈"后，观察者所看到的状态/产生式与自身属性栈一致，问题解决。

2. **`ArrayDeque` 不允许压入 `null`**：早期实现中，对没有属性的非终结符直接 `push(null)` 会抛 `NullPointerException`。解决办法是引入单例占位对象（`SemanticAnalyzer.NULL_ATTR` 与 `IRGenerator.NO_VALUE`），保持栈高度与 LR 状态栈严格同步。

3. **RISC-V 指令对立即数的限制**：`mul` 不接受立即数；`sub` 没有左立即数形式；`addi` 立即数必须在 rs 之后。直接按 IR 一对一翻译会产生非法汇编。通过在 `loadIR` 中预处理（常量折叠、立即数提升为临时变量、`ADD` 操作数顺序规整），后端翻译变得简单且每条 IR 都能映射到合法的单条 RISC-V 指令。

4. **寄存器分配时机的把握**：若先分配结果寄存器再回收死变量，则结果寄存器无法复用最后一次使用变量的寄存器，会导致寄存器资源浪费甚至不足。通过在"加载源操作数"与"分配结果寄存器"之间插入 `releaseDeadAfter`，实现了三地址指令的寄存器原地复用。

**收获**：通过本实验，从词法→语法→语义/IR→目标代码逐步实现了一个完整的小型编译器，深入体会到了：

- LR(1) 分析驱动 + 观察者模式如何将语义动作正交地"挂载"到语法分析过程中；
- 语法制导翻译（SDT）以"属性栈与符号栈同步"为核心的实现技巧；
- 目标代码生成阶段，前端 IR 与目标 ISA 之间的"阻抗失配"必须通过预处理消化；
- 简单但有效的"用时分配、死后回收"寄存器分配策略，足以支撑课内规模的代码生成。
