package cn.edu.hitsz.compiler.lexer;

import cn.edu.hitsz.compiler.NotImplementedException;
import cn.edu.hitsz.compiler.symtab.SymbolTable;
import cn.edu.hitsz.compiler.utils.FileUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;
import java.util.ArrayList;

/**
 * TODO: 实验一: 实现词法分析
 * <br>
 * 你可能需要参考的框架代码如下:
 *
 * @see Token 词法单元的实现
 * @see TokenKind 词法单元类型的实现
 */
public class LexicalAnalyzer {
    private final SymbolTable symbolTable;
    private String sourceCode;
    private ArrayList<Token> tokens;

    public LexicalAnalyzer(SymbolTable symbolTable) {
        this.symbolTable = symbolTable;
        this.tokens = new ArrayList<>();
    }


    /**
     * 从给予的路径中读取并加载文件内容
     *
     * @param path 路径
     */
    public void loadFile(String path) {
        // TODO: 词法分析前的缓冲区实现
        // 可自由实现各类缓冲区
        // 或直接采用完整读入方法
        try {
            BufferedReader br = new BufferedReader(new FileReader(path));
            StringBuilder sb = new StringBuilder();
            String line;
            try {
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            br.close();
            this.sourceCode = sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println(this.sourceCode);
    }

    /**
     * 执行词法分析, 准备好用于返回的 token 列表 <br>
     * 需要维护实验一所需的符号表条目, 而得在语法分析中才能确定的符号表条目的成员可以先设置为 null
     */
    public void run() {
        // TODO: 自动机实现的词法分析过程
        Set<String> keywords = Set.of("int", "return");
        int state = 0;
        int begin_p = 0;
        int end_p = 0;
        while (end_p < sourceCode.length()) {
            char c = sourceCode.charAt(end_p);
            switch (state) {
            case 0:
                if (Character.isWhitespace(c)) {
                    begin_p++;
                    end_p++;
                } else if (Character.isLetter(c)) {
                    state = 14;
                    end_p++;
                } else if (Character.isDigit(c)) {
                    state = 16;
                    end_p++;
                } else {
                    switch (c) {
                    case '+':
                        tokens.add(Token.simple("+"));
                        break;
                    case '-':
                        tokens.add(Token.simple("-"));
                        break;
                    case '*':
                        tokens.add(Token.simple("*"));
                        break;
                    case '/':
                        tokens.add(Token.simple("/"));
                        break;  
                    case '=':
                        tokens.add(Token.simple("="));
                        break;
                    case '(':
                        tokens.add(Token.simple("("));
                        break;  
                    case ')':
                        tokens.add(Token.simple(")"));
                        break;
                    case ',':
                        tokens.add(Token.simple(","));
                        break;
                    case ';':
                        tokens.add(Token.simple("Semicolon"));
                        break;
                    }
                    begin_p++;
                    end_p++;
                }
                break;
            case 14:
                if (Character.isLetterOrDigit(c)) {
                    end_p++;
                } else {
                    String ident = sourceCode.substring(begin_p, end_p);
                    if (keywords.contains(ident)) {
                        tokens.add(Token.simple(ident));
                    } else {
                        tokens.add(Token.normal("id", ident));
                        if (!symbolTable.has(ident)) {
                            symbolTable.add(ident);
                        }
                    }
                    begin_p = end_p;
                    state = 0;
                }
                break;
            case 16:
                if (Character.isDigit(c)) {
                    end_p++;
                } else {
                    String intConst = sourceCode.substring(begin_p, end_p);
                    tokens.add(Token.normal("IntConst", intConst));
                    state = 0;
                    begin_p = end_p;
                }
                break;
            }
        }
        tokens.add(Token.eof());        
    }

    /**
     * 获得词法分析的结果, 保证在调用了 run 方法之后调用
     *
     * @return Token 列表
     */
    public Iterable<Token> getTokens() {
        // TODO: 从词法分析过程中获取 Token 列表
        // 词法分析过程可以使用 Stream 或 Iterator 实现按需分析
        // 亦可以直接分析完整个文件
        // 总之实现过程能转化为一列表即可
        return tokens;
    }

    public void dumpTokens(String path) {
        FileUtils.writeLines(
            path,
            StreamSupport.stream(getTokens().spliterator(), false).map(Token::toString).toList()
        );
    }


}
