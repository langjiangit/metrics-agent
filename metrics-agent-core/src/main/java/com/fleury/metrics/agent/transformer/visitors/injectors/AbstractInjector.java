package com.fleury.metrics.agent.transformer.visitors.injectors;

import static com.fleury.metrics.agent.model.LabelUtil.getLabelVarIndex;
import static com.fleury.metrics.agent.model.LabelUtil.getNestedLabelVar;
import static com.fleury.metrics.agent.model.LabelUtil.isLabelVarNested;
import static com.fleury.metrics.agent.model.LabelUtil.isTemplatedLabelValue;
import static com.fleury.metrics.agent.model.LabelUtil.isThis;
import static com.fleury.metrics.agent.transformer.util.CollectionUtil.isNotEmpty;

import com.fleury.metrics.agent.introspector.GenericClassIntrospector;
import com.fleury.metrics.agent.model.LabelUtil;
import com.fleury.metrics.agent.model.Metric;
import com.fleury.metrics.agent.reporter.Reporter;
import com.fleury.metrics.agent.transformer.util.OpCodeUtil;
import java.util.List;
import org.apache.commons.beanutils.PropertyUtils;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 *
 * @author Will Fleury
 */
public abstract class AbstractInjector implements Injector, Opcodes {

    public static final String METRIC_REPORTER_CLASSNAME = Type.getInternalName(Reporter.class);

    static {
        PropertyUtils.addBeanIntrospector(new GenericClassIntrospector());
    }

    protected final AdviceAdapter aa;
    protected final Type[] argTypes;
    protected final int access;

    public AbstractInjector(AdviceAdapter aa, Type[] argTypes, int access) {
        this.aa = aa;
        this.argTypes = argTypes;
        this.access = access;
    }

    @Override
    public void injectAtMethodEnter() {
    }

    @Override
    public void injectAtVisitMaxs(int maxStack, int maxLocals) {
    }

    @Override
    public void injectAtMethodExit(int opcode) {
    }

    protected void injectNameAndLabelToStack(Metric metric) {
        int nameVar = aa.newLocal(Type.getType(String.class));
        aa.visitLdcInsn(metric.getName());
        aa.visitVarInsn(ASTORE, nameVar);

        List<String> labelValues = LabelUtil.getLabelValues(metric.getLabels());

        if (isNotEmpty(labelValues)) {
            int labelVar = injectLabelValuesArrayToStack(metric, labelValues);

            aa.visitVarInsn(ALOAD, nameVar);
            aa.visitVarInsn(ALOAD, labelVar);
        } else {
            aa.visitVarInsn(ALOAD, nameVar);
            aa.visitInsn(ACONST_NULL);
        }
    }

    protected int injectLabelValuesArrayToStack(Metric metric, List<String> labelValues) {
        if (labelValues.size() > 5) {
            throw new IllegalStateException("Maximum labels per metric is 5. "
                    + metric.getName() + " has " + labelValues.size());
        }
        int labelVar = aa.newLocal(Type.getType(String[].class));

        aa.visitInsn(OpCodeUtil.getIConstOpcodeForInteger(labelValues.size()));
        aa.visitTypeInsn(ANEWARRAY, Type.getInternalName(String.class));

        for (int i = 0; i < labelValues.size(); i++) {
            aa.visitInsn(DUP);
            aa.visitInsn(OpCodeUtil.getIConstOpcodeForInteger(i));
            injectLabelValueToStack(labelValues.get(i));
        }

        aa.visitVarInsn(ASTORE, labelVar);

        return labelVar;
    }

    private void injectLabelValueToStack(String labelValue) {
        if (!isTemplatedLabelValue(labelValue)) {
            aa.visitLdcInsn(labelValue);
        } 
        else {
            if (isThis(labelValue)) {
                aa.visitVarInsn(ALOAD, 0); //aa.loadThis();
            }
            
            else {
                int argIndex = getLabelVarIndex(labelValue);

                boxParameterAndLoad(argIndex);
            }

            if (isLabelVarNested(labelValue)) {
                aa.visitLdcInsn(getNestedLabelVar(labelValue));

                aa.visitMethodInsn(INVOKESTATIC, Type.getInternalName(PropertyUtils.class),
                        "getNestedProperty",
                        Type.getMethodDescriptor(
                                Type.getType(Object.class),
                                Type.getType(Object.class), Type.getType(String.class)),
                        false);
            }

            aa.visitMethodInsn(INVOKESTATIC, Type.getInternalName(String.class),
                    "valueOf",
                    Type.getMethodDescriptor(
                            Type.getType(String.class),
                            Type.getType(Object.class)),
                    false);
        }
       
        aa.visitInsn(AASTORE);
    }

    private void boxParameterAndLoad(int argIndex) {
        Type type = argTypes[argIndex];
        int stackIndex = getStackIndex(argIndex);
        
        switch (type.getSort()) {
            case Type.OBJECT: //no need to box Object
                aa.visitVarInsn(ALOAD, stackIndex);  
                break;
                
            default:
                // aa.loadArg(argIndex); //doesn't work...
                aa.visitVarInsn(type.getOpcode(Opcodes.ILOAD), stackIndex);
                aa.valueOf(type);
                break;
        }
    }
    
    private int getStackIndex(int arg) {
        int index = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (int i = 0; i < arg; i++) {
            index += argTypes[i].getSize();
        }
        return index;
    }
}
