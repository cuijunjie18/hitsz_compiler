package cn.edu.hitsz.compiler.parser;

import cn.edu.hitsz.compiler.lexer.Token;
import cn.edu.hitsz.compiler.parser.table.Production;
import cn.edu.hitsz.compiler.parser.table.Status;
import cn.edu.hitsz.compiler.symtab.SourceCodeType;
import cn.edu.hitsz.compiler.symtab.SymbolTable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 语义分析器：对文法中的声明语句 {@code S -> D id} 进行类型填充。
 * <br>
 * 实现思路：在 LR 分析过程中维护一个与 LR 状态栈平行的 "符号信息栈"，
 * 栈中元素用来保存对后续规约有用的属性：
 * <ul>
 *     <li>对终结符, 保存对应的 {@link Token}, 以便在规约时取回 id 的文本等信息</li>
 *     <li>对非终结符 {@code D}, 保存其综合属性 {@link SourceCodeType}</li>
 *     <li>其他非终结符对语义分析没有意义, 压入 {@code null} 作为占位</li>
 * </ul>
 * 当规约到 {@code S -> D id} 时, 从栈顶取到 id 的 Token 及 D 的类型, 再去符号表中为 id 填充类型。
 */
public class SemanticAnalyzer implements ActionObserver {
    // 符号信息栈：元素可能是 Token (终结符) 或 SourceCodeType (非终结符 D), 或 null (其它非终结符)
    private final Deque<Object> symbolStack = new ArrayDeque<>();
    private SymbolTable symbolTable;

    @Override
    public void whenAccept(Status currentStatus) {
        // 接受时无需再做语义动作
    }

    @Override
    public void whenReduce(Status currentStatus, Production production) {
        // 先按产生式体的长度把参与规约的符号从栈中弹出, 同时保留下来以供动作使用
        final int bodyLength = production.body().size();
        final Object[] rhs = new Object[bodyLength];
        // 栈顶是产生式最右边的符号, 这里按从左到右的顺序存入 rhs
        for (int i = bodyLength - 1; i >= 0; i--) {
            rhs[i] = symbolStack.pop();
        }

        // 根据产生式执行对应的语义动作, 并决定压入栈的新属性
        final String head = production.head().getTermName();
        final String bodyStr = production.toString();

        Object headAttribute = null;

        switch (bodyStr) {
            case "D -> int" -> {
                // D 的综合属性是 Int 类型
                headAttribute = SourceCodeType.Int;
            }
            case "S -> D id" -> {
                // 为 id 对应的符号表条目填充类型
                final SourceCodeType type = (SourceCodeType) rhs[0];
                final Token idToken = (Token) rhs[1];
                final String name = idToken.getText();
                if (!symbolTable.has(name)) {
                    throw new RuntimeException("Declared identifier not in symbol table: " + name);
                }
                symbolTable.get(name).setType(type);
            }
            default -> {
                // 其它产生式对语义分析无影响
            }
        }

        // 压入规约得到的非终结符的属性 (大多数情况下为 null)
        symbolStack.push(wrap(headAttribute));
        // head 暂时未使用, 保留以便调试
        assert head != null;
    }

    @Override
    public void whenShift(Status currentStatus, Token currentToken) {
        // 把当前终结符的 Token 压入符号信息栈, 以便之后的规约动作能取到它
        symbolStack.push(currentToken);
    }

    @Override
    public void setSymbolTable(SymbolTable table) {
        this.symbolTable = table;
    }

    /**
     * 为了让 {@link ArrayDeque} 能压入 "null 语义值" 而准备的包装:
     * ArrayDeque 不允许存 null, 我们用一个单例对象代替.
     */
    private static final Object NULL_ATTR = new Object();

    private static Object wrap(Object value) {
        return value == null ? NULL_ATTR : value;
    }
}

