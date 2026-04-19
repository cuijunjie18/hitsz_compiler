package cn.edu.hitsz.compiler.parser;

import cn.edu.hitsz.compiler.lexer.Token;
import cn.edu.hitsz.compiler.parser.table.Action;
import cn.edu.hitsz.compiler.parser.table.LRTable;
import cn.edu.hitsz.compiler.parser.table.Production;
import cn.edu.hitsz.compiler.parser.table.Status;
import cn.edu.hitsz.compiler.symtab.SymbolTable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

//TODO: 实验二: 实现 LR 语法分析驱动程序

/**
 * LR 语法分析驱动程序
 * <br>
 * 该程序接受词法单元串与 LR 分析表 (action 和 goto 表), 按表对词法单元流进行分析, 执行对应动作, 并在执行动作时通知各注册的观察者.
 * <br>
 * 你应当按照被挖空的方法的文档实现对应方法, 你可以随意为该类添加你需要的私有成员对象, 但不应该再为此类添加公有接口, 也不应该改动未被挖空的方法,
 * 除非你已经同助教充分沟通, 并能证明你的修改的合理性, 且令助教确定可能被改动的评测方法. 随意修改该类的其它部分有可能导致自动评测出错而被扣分.
 */
public class SyntaxAnalyzer {
    private final SymbolTable symbolTable;
    private final List<ActionObserver> observers = new ArrayList<>();

    // 待分析的词法单元队列
    private final Queue<Token> tokenQueue = new LinkedList<>();
    // LR 分析表
    private LRTable lrTable;


    public SyntaxAnalyzer(SymbolTable symbolTable) {
        this.symbolTable = symbolTable;
    }

    /**
     * 注册新的观察者
     *
     * @param observer 观察者
     */
    public void registerObserver(ActionObserver observer) {
        observers.add(observer);
        observer.setSymbolTable(symbolTable);
    }

    /**
     * 在执行 shift 动作时通知各个观察者
     *
     * @param currentStatus 当前状态
     * @param currentToken  当前词法单元
     */
    public void callWhenInShift(Status currentStatus, Token currentToken) {
        for (final var listener : observers) {
            listener.whenShift(currentStatus, currentToken);
        }
    }

    /**
     * 在执行 reduce 动作时通知各个观察者
     *
     * @param currentStatus 当前状态
     * @param production    待规约的产生式
     */
    public void callWhenInReduce(Status currentStatus, Production production) {
        for (final var listener : observers) {
            listener.whenReduce(currentStatus, production);
        }
    }

    /**
     * 在执行 accept 动作时通知各个观察者
     *
     * @param currentStatus 当前状态
     */
    public void callWhenInAccept(Status currentStatus) {
        for (final var listener : observers) {
            listener.whenAccept(currentStatus);
        }
    }

    public void loadTokens(Iterable<Token> tokens) {
        // TODO: 加载词法单元
        // 你可以自行选择要如何存储词法单元, 譬如使用迭代器, 或是栈, 或是干脆使用一个 list 全存起来
        // 需要注意的是, 在实现驱动程序的过程中, 你会需要面对只读取一个 token 而不能消耗它的情况,
        // 在自行设计的时候请加以考虑此种情况

        // 将传入的词法单元按顺序收集进队列中, 以便 run() 过程中按需逐个消费
        // 使用队列可以方便地 peek 当前 token 而不消耗它, 也可以在需要时再 poll 出来
        tokenQueue.clear();
        for (final var token : tokens) {
            tokenQueue.offer(token);
        }
    }

    public void loadLRTable(LRTable table) {
        // TODO: 加载 LR 分析表
        // 你可以自行选择要如何使用该表格:
        // 是直接对 LRTable 调用 getAction/getGoto, 抑或是直接将 initStatus 存起来使用

        // 直接持有 LR 分析表的引用, 后续通过 Status 自身的 getAction/getGoto 进行查询
        this.lrTable = table;
    }

    public void run() {
        // TODO: 实现驱动程序
        // 你需要根据上面的输入来实现 LR 语法分析的驱动程序
        // 请分别在遇到 Shift, Reduce, Accept 的时候调用上面的 callWhenInShift, callWhenInReduce, callWhenInAccept
        // 否则用于为实验二打分的产生式输出可能不会正常工作
        // LR 分析驱动程序
        // 维护一个状态栈, 初始只包含 LR 表的起始状态
        final Deque<Status> statusStack = new ArrayDeque<>();
        statusStack.push(lrTable.getInit());

        // 主循环: 不断根据栈顶状态与当前待分析 token 查找并执行 Action
        while (true) {
            final Status currentStatus = statusStack.peek();
            if (currentStatus == null) {
                throw new RuntimeException("Status stack is empty during LR parsing");
            }

            // 查看 (不消耗) 队首的 token
            final Token currentToken = tokenQueue.peek();
            if (currentToken == null) {
                throw new RuntimeException("Token queue is empty but parsing has not accepted yet");
            }

            final Action action = lrTable.getAction(currentStatus, currentToken);

            switch (action.getKind()) {
                case Shift -> {
                    // 移入: 通知观察者, 消耗当前 token, 将目标状态压栈
                    callWhenInShift(currentStatus, currentToken);
                    tokenQueue.poll();
                    statusStack.push(action.getStatus());
                }

                case Reduce -> {
                    // 规约: 通知观察者, 按产生式体长度弹出状态, 再用栈顶状态与产生式头查 GOTO 压入新状态
                    final Production production = action.getProduction();
                    callWhenInReduce(currentStatus, production);

                    final int bodyLength = production.body().size();
                    for (int i = 0; i < bodyLength; i++) {
                        statusStack.pop();
                    }

                    final Status topAfterPop = statusStack.peek();
                    if (topAfterPop == null) {
                        throw new RuntimeException("Status stack is empty after reduce pops");
                    }
                    final Status gotoStatus = topAfterPop.getGoto(production.head());
                    if (gotoStatus.isError()) {
                        throw new RuntimeException(
                            "No goto entry for non-terminal " + production.head()
                                + " at status " + topAfterPop);
                    }
                    statusStack.push(gotoStatus);
                }

                case Accept -> {
                    // 接受: 通知观察者后结束驱动
                    callWhenInAccept(currentStatus);
                    return;
                }

                case Error -> throw new RuntimeException(
                    "Syntax error: unexpected token " + currentToken
                        + " at status " + currentStatus);

                default -> throw new RuntimeException("Unknown action kind: " + action.getKind());
            }
        }
    }
}
