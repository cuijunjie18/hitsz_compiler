package cn.edu.hitsz.compiler.asm;

import cn.edu.hitsz.compiler.ir.IRImmediate;
import cn.edu.hitsz.compiler.ir.IRValue;
import cn.edu.hitsz.compiler.ir.IRVariable;
import cn.edu.hitsz.compiler.ir.Instruction;
import cn.edu.hitsz.compiler.ir.InstructionKind;
import cn.edu.hitsz.compiler.utils.FileUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * 实验四: 实现汇编生成
 * <br>
 * 在编译器的整体框架中, 代码生成可以称作后端, 而前面的所有工作都可称为前端.
 * <br>
 * 在前端完成的所有工作中, 都是与目标平台无关的, 而后端的工作为将前端生成的目标平台无关信息
 * 根据目标平台生成汇编代码. 前后端的分离有利于实现编译器面向不同平台生成汇编代码. 由于前后
 * 端分离的原因, 有可能前端生成的中间代码并不符合目标平台的汇编代码特点. 具体到本项目你可以
 * 尝试加入一个方法将中间代码调整为更接近 risc-v 汇编的形式, 这样会有利于汇编代码的生成.
 *
 * @see AssemblyGenerator#run() 代码生成与寄存器分配
 */
public class AssemblyGenerator {

    /** 可用于分配的通用寄存器池 (RISC-V 中的临时寄存器) */
    private static final List<String> AVAILABLE_REGS = List.of(
            "t0", "t1", "t2", "t3", "t4", "t5", "t6"
    );

    /** 预处理后的 IR 序列 */
    private final List<Instruction> instructions = new ArrayList<>();

    /** 生成的汇编代码 (含注释) */
    private final List<String> assembly = new ArrayList<>();

    /** 寄存器名 -> 当前所持有的 IR 变量 (null 表示空闲) */
    private final Map<String, IRVariable> regToVar = new HashMap<>();

    /** IR 变量 -> 当前所在寄存器 */
    private final Map<IRVariable, String> varToReg = new HashMap<>();


    /**
     * 加载前端提供的中间代码
     * <br>
     * 此处会将不太适合 RISC-V 直接生成的中间代码做一些预处理, 例如:
     * <ul>
     *     <li>对于双立即数的 ADD/SUB/MUL, 直接编译期算出结果转为 MOV</li>
     *     <li>对于左操作数为立即数的 SUB, 需要先 li 到一个临时变量</li>
     *     <li>对于乘法 MUL, 任何立即数操作数都要先 li 到临时变量 (RISC-V 中 mul 不支持立即数)</li>
     *     <li>对于 ADD, 如果两个操作数中只有一个是立即数, 调整为 (变量, 立即数) 的顺序方便生成 addi</li>
     *     <li>RET 之后的所有指令都是死代码, 直接丢弃</li>
     * </ul>
     *
     * @param originInstructions 前端提供的中间代码
     */
    public void loadIR(List<Instruction> originInstructions) {
        instructions.clear();
        for (final var inst : originInstructions) {
            final var kind = inst.getKind();

            if (kind.isReturn()) {
                instructions.add(inst);
                // RET 之后是死代码
                break;
            }

            if (kind == InstructionKind.MOV) {
                instructions.add(inst);
                continue;
            }

            // 二元运算
            IRValue lhs = inst.getLHS();
            IRValue rhs = inst.getRHS();
            final var result = inst.getResult();

            // 双立即数: 折叠成 MOV
            if (lhs.isImmediate() && rhs.isImmediate()) {
                final int l = ((IRImmediate) lhs).getValue();
                final int r = ((IRImmediate) rhs).getValue();
                final int v;
                switch (kind) {
                    case ADD -> v = l + r;
                    case SUB -> v = l - r;
                    case MUL -> v = l * r;
                    default -> throw new RuntimeException("unexpected kind: " + kind);
                }
                instructions.add(Instruction.createMov(result, IRImmediate.of(v)));
                continue;
            }

            // ADD: 若左立即数右变量, 交换 (利用加法交换律), 方便生成 addi
            if (kind == InstructionKind.ADD && lhs.isImmediate() && rhs.isIRVariable()) {
                final var tmp = lhs;
                lhs = rhs;
                rhs = tmp;
                instructions.add(Instruction.createAdd(result, lhs, rhs));
                continue;
            }

            // SUB: 若左立即数, 需要先把立即数 li 到一个临时变量
            if (kind == InstructionKind.SUB && lhs.isImmediate()) {
                final var temp = IRVariable.temp();
                instructions.add(Instruction.createMov(temp, lhs));
                instructions.add(Instruction.createSub(result, temp, rhs));
                continue;
            }

            // MUL: 任意一边的立即数都需要先 li 到临时变量 (RISC-V 没有 muli)
            if (kind == InstructionKind.MUL) {
                if (lhs.isImmediate()) {
                    final var temp = IRVariable.temp();
                    instructions.add(Instruction.createMov(temp, lhs));
                    lhs = temp;
                }
                if (rhs.isImmediate()) {
                    final var temp = IRVariable.temp();
                    instructions.add(Instruction.createMov(temp, rhs));
                    rhs = temp;
                }
                instructions.add(Instruction.createMul(result, lhs, rhs));
                continue;
            }

            // 其他正常情况
            instructions.add(inst);
        }
    }


    /**
     * 执行代码生成.
     * <br>
     * 寄存器分配采用 "首次使用时分配, 之后不再使用时回收" 的策略:
     * 在每条指令处理前, 计算各 IR 变量在该指令(含)以后是否还会被读取,
     * 如果不再被读取, 则其占用的寄存器即可回收用于本指令结果的存放.
     */
    public void run() {
        assembly.clear();
        regToVar.clear();
        varToReg.clear();
        assembly.add(".text");

        // 预先初始化所有可用寄存器为空闲
        for (final var r : AVAILABLE_REGS) {
            regToVar.put(r, null);
        }

        for (int i = 0; i < instructions.size(); i++) {
            final var inst = instructions.get(i);
            final var kind = inst.getKind();

            if (kind.isReturn()) {
                final var rv = inst.getReturnValue();
                final String src;
                if (rv.isImmediate()) {
                    // 返回立即数: 直接 li 到 a0
                    final int v = ((IRImmediate) rv).getValue();
                    assembly.add("    li a0, %d\t\t#  %s".formatted(v, inst.toString()));
                    continue;
                } else {
                    src = ensureLoaded((IRVariable) rv, i);
                    assembly.add("    mv a0, %s\t\t#  %s".formatted(src, inst.toString()));
                    continue;
                }
            }

            if (kind == InstructionKind.MOV) {
                final var from = inst.getFrom();
                final var result = inst.getResult();
                if (from.isImmediate()) {
                    // 在分配 result 寄存器之前, 先释放本指令之后不再被使用的变量
                    releaseDeadAfter(i);
                    final var rd = allocate(result);
                    assembly.add("    li %s, %d\t\t#  %s".formatted(rd, ((IRImmediate) from).getValue(), inst.toString()));
                } else {
                    final var rs = ensureLoaded((IRVariable) from, i);
                    releaseDeadAfter(i);
                    final var rd = allocate(result);
                    assembly.add("    mv %s, %s\t\t#  %s".formatted(rd, rs, inst.toString()));
                }
                continue;
            }

            // 二元: ADD / SUB / MUL
            final var lhs = inst.getLHS();
            final var rhs = inst.getRHS();
            final var result = inst.getResult();

            // 加载源操作数所在寄存器 (此时还不能释放: 否则可能影响下面 allocate 的判断)
            final String rsLhs = lhs.isIRVariable() ? ensureLoaded((IRVariable) lhs, i) : null;
            final String rsRhs = rhs.isIRVariable() ? ensureLoaded((IRVariable) rhs, i) : null;

            releaseDeadAfter(i);
            final var rd = allocate(result);

            switch (kind) {
                case ADD -> {
                    if (rhs.isImmediate()) {
                        // addi rd, rsLhs, imm
                        final int imm = ((IRImmediate) rhs).getValue();
                        assembly.add("    addi %s, %s, %d\t\t#  %s".formatted(rd, rsLhs, imm, inst.toString()));
                    } else if (lhs.isImmediate()) {
                        // 经过预处理, 这种情况不会出现, 但兜底
                        final int imm = ((IRImmediate) lhs).getValue();
                        assembly.add("    addi %s, %s, %d\t\t#  %s".formatted(rd, rsRhs, imm, inst.toString()));
                    } else {
                        assembly.add("    add %s, %s, %s\t\t#  %s".formatted(rd, rsLhs, rsRhs, inst.toString()));
                    }
                }
                case SUB -> {
                    if (rhs.isImmediate()) {
                        // 用 addi rd, rs, -imm 实现 subi
                        final int imm = ((IRImmediate) rhs).getValue();
                        assembly.add("    addi %s, %s, %d\t\t#  %s".formatted(rd, rsLhs, -imm, inst.toString()));
                    } else {
                        assembly.add("    sub %s, %s, %s\t\t#  %s".formatted(rd, rsLhs, rsRhs, inst.toString()));
                    }
                }
                case MUL -> assembly.add("    mul %s, %s, %s\t\t#  %s".formatted(rd, rsLhs, rsRhs, inst.toString()));
                default -> throw new RuntimeException("unexpected binary kind: " + kind);
            }
        }
    }


    /**
     * 输出汇编代码到文件
     *
     * @param path 输出文件路径
     */
    public void dump(String path) {
        FileUtils.writeLines(path, assembly);
    }


    //==================================== 寄存器分配辅助 ========================================//

    /**
     * 确保给定 IR 变量已经在某个寄存器中, 返回该寄存器名.
     * 如果变量当前不在寄存器中 (理论上不会发生, 因为变量在被写之前不会被读), 抛出异常.
     */
    private String ensureLoaded(IRVariable var, int currentIdx) {
        final var reg = varToReg.get(var);
        if (reg == null) {
            throw new RuntimeException("Variable " + var + " used before defined at instruction " + currentIdx);
        }
        return reg;
    }

    /**
     * 释放在第 currentIdx 条指令之后(不含)不再被读取的所有变量所占的寄存器.
     */
    private void releaseDeadAfter(int currentIdx) {
        final var toRelease = new ArrayList<IRVariable>();
        for (final var entry : varToReg.entrySet()) {
            final var v = entry.getKey();
            if (!isUsedAfter(v, currentIdx)) {
                toRelease.add(v);
            }
        }
        for (final var v : toRelease) {
            final var reg = varToReg.remove(v);
            regToVar.put(reg, null);
        }
    }

    /**
     * 判断变量在 currentIdx 之后(不含 currentIdx)是否还会被读取.
     */
    private boolean isUsedAfter(IRVariable var, int currentIdx) {
        for (int j = currentIdx + 1; j < instructions.size(); j++) {
            final var inst = instructions.get(j);
            for (final var op : inst.getOperands()) {
                if (op.equals(var)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 为指定 IR 变量分配一个寄存器.
     * 若已分配则直接返回; 否则寻找一个空闲寄存器.
     */
    private String allocate(IRVariable var) {
        final var existed = varToReg.get(var);
        if (existed != null) {
            return existed;
        }
        for (final var r : AVAILABLE_REGS) {
            if (regToVar.get(r) == null) {
                regToVar.put(r, var);
                varToReg.put(var, r);
                return r;
            }
        }
        throw new RuntimeException("No available register to allocate for " + var);
    }
}
