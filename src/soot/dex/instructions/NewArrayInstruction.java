/* Soot - a Java Optimization Framework
 * Copyright (C) 2012 Michael Markert, Frank Hartmann
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA 02111-1307, USA.
 */

package soot.dex.instructions;

import java.util.HashSet;
import java.util.Set;

import org.jf.dexlib.TypeIdItem;
import org.jf.dexlib.Code.Instruction;
import org.jf.dexlib.Code.InstructionWithReference;
import org.jf.dexlib.Code.TwoRegisterInstruction;
import org.jf.dexlib.Code.Format.Instruction22c;

import soot.ArrayType;
import soot.Local;
import soot.Type;
import soot.Value;
import soot.dex.Debug;
import soot.dex.DexBody;
import soot.dex.DexType;
import soot.dex.DvkTyper;
import soot.jimple.AssignStmt;
import soot.jimple.Jimple;
import soot.jimple.NewArrayExpr;
import soot.jimple.internal.JAssignStmt;

public class NewArrayInstruction extends DexlibAbstractInstruction {

    public NewArrayInstruction (Instruction instruction, int codeAdress) {
        super(instruction, codeAdress);
    }

    public void jimplify (DexBody body) {

        if(!(instruction instanceof Instruction22c))
            throw new IllegalArgumentException("Expected Instruction22c but got: "+instruction.getClass());

        Instruction22c newArray = (Instruction22c)instruction;
        int dest = newArray.getRegisterA();

        Value size = body.getRegisterLocal(newArray.getRegisterB());

        Type t = DexType.toSoot((TypeIdItem) newArray.getReferencedItem());
        // NewArrayExpr needs the ElementType as it increases the array dimension by 1
        Type arrayType = ((ArrayType) t).getElementType();
        Debug.printDbg("array element type: "+ arrayType);

        NewArrayExpr newArrayExpr = Jimple.v().newNewArrayExpr(arrayType, size);

        Local l = body.getRegisterLocal(dest);
        AssignStmt assign = Jimple.v().newAssignStmt(l, newArrayExpr);

        defineBlock(assign);
        tagWithLineNumber(assign);
        body.add(assign);
        if (DvkTyper.ENABLE_DVKTYPER) {
          int op = (int)instruction.opcode.value;
          body.captureAssign((JAssignStmt)assign, op); // TODO: ref. type may be null!
        }
    }

    @Override
    boolean overridesRegister(int register) {
        TwoRegisterInstruction i = (TwoRegisterInstruction) instruction;
        int dest = i.getRegisterA();
        return register == dest;
    }

    @Override
    public Set<DexType> introducedTypes() {
        InstructionWithReference i = (InstructionWithReference) instruction;

        Set<DexType> types = new HashSet<DexType>();
        types.add(new DexType((TypeIdItem) i.getReferencedItem()));
        return types;
    }
}
