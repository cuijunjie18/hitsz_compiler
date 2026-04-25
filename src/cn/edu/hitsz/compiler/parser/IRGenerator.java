package cn.edu.hitsz.compiler.parser;

import cn.edu.hitsz.compiler.ir.IRImmediate;
import cn.edu.hitsz.compiler.ir.IRValue;
import cn.edu.hitsz.compiler.ir.IRVariable;
import cn.edu.hitsz.compiler.ir.Instruction;
import cn.edu.hitsz.compiler.lexer.Token;
import cn.edu.hitsz.compiler.parser.table.Production;
import cn.edu.hitsz.compiler.parser.table.Status;
import cn.edu.hitsz.compiler.symtab.SymbolTable;
import cn.edu.hitsz.compiler.utils.FileUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * IR 生成器：在 LR 分析过程中按产生式生成三地址中间代码 (IR)。
 * <br>
 * 实现方式参考语法制导翻译 (SDT)：维护一个与 LR 栈同步的属性栈 {@code attrStack},
 * 每个文法符号对应一个可选的 {@link IRValue} 综合属性。
 * <ul>
 *     <li>终结符 {@code id}/{@code IntConst} 在 shift 时把对应 IRValue 压栈;
 *         其它终结符只压一个占位符, 因为它们没有值属性。</li>
 *     <li>非终结符 {@code E}/{@code A}/{@code B} 的属性为计算结果对应的 IRValue;
 *         其它非终结符属性为占位符。</li>
 * </ul>
 * 规约时根据产生式的字符串形式分派具体语义动作，并向 {@link #instructions} 中追加相应指令。
 */
public class IRGenerator implements ActionObserver {
    // 属性栈: 与 LR 栈平行, 每个元素保存对应文法符号的综合属性 (IRValue 或占位符)
    private final Deque<IRValue> attrStack = new ArrayDeque<>();
    // 生成出的 IR 指令列表
    private final List<Instruction> instructions = new ArrayList<>();

    // 无属性时使用的占位符, 因为 ArrayDeque 不允许 null
    private static final IRValue NO_VALUE = new IRValue() {
        @Override
        public String toString() {
            return "<no-value>";
        }
    };

    @SuppressWarnings("unused")
    private SymbolTable symbolTable;

    @Override
    public void whenShift(Status currentStatus, Token currentToken) {
        // 根据终结符种类决定压栈的属性
        final String kind = currentToken.getKindId();
        switch (kind) {
            case "id" -> attrStack.push(IRVariable.named(currentToken.getText()));
            case "IntConst" -> attrStack.push(IRImmediate.of(Integer.parseInt(currentToken.getText())));
            default -> attrStack.push(NO_VALUE);
        }
    }

    @Override
    public void whenReduce(Status currentStatus, Production production) {
        // 按产生式体长度弹出参与规约的属性, 保存为从左到右的顺序以便按位访问
        final int bodyLength = production.body().size();
        final IRValue[] rhs = new IRValue[bodyLength];
        for (int i = bodyLength - 1; i >= 0; i--) {
            rhs[i] = attrStack.pop();
        }

        final String productionStr = production.toString();
        IRValue headAttr = NO_VALUE;

        switch (productionStr) {
            // 表达式部分 ------------------------------------------------------------
            case "B -> id", "B -> IntConst" -> {
                // B 直接继承终结符的属性
                headAttr = rhs[0];
            }
            case "B -> ( E )" -> {
                // B 继承括号里 E 的属性
                headAttr = rhs[1];
            }
            case "A -> B", "E -> A" -> {
                // 单继承产生式, 直接传递属性
                headAttr = rhs[0];
            }
            case "A -> A * B" -> {
                final IRVariable temp = IRVariable.temp();
                instructions.add(Instruction.createMul(temp, rhs[0], rhs[2]));
                headAttr = temp;
            }
            case "E -> E + A" -> {
                final IRVariable temp = IRVariable.temp();
                instructions.add(Instruction.createAdd(temp, rhs[0], rhs[2]));
                headAttr = temp;
            }
            case "E -> E - A" -> {
                final IRVariable temp = IRVariable.temp();
                instructions.add(Instruction.createSub(temp, rhs[0], rhs[2]));
                headAttr = temp;
            }

            // 语句部分 --------------------------------------------------------------
            case "S -> id = E" -> {
                // rhs[0] 为 id 对应的 IRVariable, rhs[2] 为 E 的结果
                final IRVariable target = (IRVariable) rhs[0];
                instructions.add(Instruction.createMov(target, rhs[2]));
            }
            case "S -> return E" -> {
                instructions.add(Instruction.createRet(rhs[1]));
            }

            // 其它产生式 (声明/串接) 无 IR 生成动作
            default -> {
            }
        }

        attrStack.push(headAttr);
    }

    @Override
    public void whenAccept(Status currentStatus) {
        // 所有规约已完成, 无需额外动作
    }

    @Override
    public void setSymbolTable(SymbolTable table) {
        this.symbolTable = table;
    }

    public List<Instruction> getIR() {
        return instructions;
    }

    public void dumpIR(String path) {
        FileUtils.writeLines(path, getIR().stream().map(Instruction::toString).toList());
    }
}

