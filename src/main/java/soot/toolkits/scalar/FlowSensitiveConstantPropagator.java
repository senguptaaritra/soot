package soot.toolkits.scalar;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import soot.Body;
import soot.BodyTransformer;
import soot.G;
import soot.Local;
import soot.Scene;
import soot.Singletons;
import soot.Unit;
import soot.Value;
import soot.ValueBox;
import soot.dexpler.DexNullArrayRefTransformer;
import soot.dexpler.DexNullThrowTransformer;
import soot.jimple.AssignStmt;
import soot.jimple.CmpgExpr;
import soot.jimple.CmplExpr;
import soot.jimple.Constant;
import soot.jimple.DoubleConstant;
import soot.jimple.FloatConstant;
import soot.jimple.IdentityStmt;
import soot.jimple.IfStmt;
import soot.jimple.IntConstant;
import soot.jimple.LongConstant;
import soot.jimple.RealConstant;
import soot.jimple.Stmt;
import soot.jimple.ThrowStmt;
import soot.jimple.internal.ImmediateBox;
import soot.options.Options;
import soot.toolkits.exceptions.ThrowAnalysis;
import soot.toolkits.graph.DirectedGraph;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.util.LocalBitSetPacker;

//@formatter:off
/**
 * A constant propagator which can cope with cases like <code>a = 2; b = a; a = b;</code>
 * 
 * as well as
 * 
 * <code>if (x) { a = 2; } else { a = 2; } b = a;</code>
 * 
 * @author Marc Miltenberger
 * @see BodyTransformer
 * @see LocalPacker
 * @see Body
 */
// @formatter:on
public class FlowSensitiveConstantPropagator extends BodyTransformer {
  private static final Logger logger = LoggerFactory.getLogger(FlowSensitiveConstantPropagator.class);

  protected ThrowAnalysis throwAnalysis;
  protected boolean omitExceptingUnitEdges;

  public FlowSensitiveConstantPropagator(Singletons.Global g) {
  }

  public FlowSensitiveConstantPropagator(ThrowAnalysis ta) {
    this(ta, false);
  }

  public FlowSensitiveConstantPropagator(ThrowAnalysis ta, boolean omitExceptingUnitEdges) {
    this.throwAnalysis = ta;
    this.omitExceptingUnitEdges = omitExceptingUnitEdges;
  }

  public static FlowSensitiveConstantPropagator v() {
    return G.v().soot_toolkits_scalar_FlowSensitiveConstantPropagator();
  }

  @Override
  protected void internalTransform(Body body, String phaseName, Map<String, String> options) {
    if (Options.v().verbose()) {
      logger.debug("[" + body.getMethod().getName() + "] Splitting for shared initialization of locals...");
    }

    if (throwAnalysis == null) {
      throwAnalysis = Scene.v().getDefaultThrowAnalysis();
    }

    if (omitExceptingUnitEdges == false) {
      omitExceptingUnitEdges = Options.v().omit_excepting_unit_edges();
    }

    final LocalBitSetPacker localPacker = new LocalBitSetPacker(body);
    localPacker.pack();

    ExceptionalUnitGraph graph = new ExceptionalUnitGraph(body, throwAnalysis, omitExceptingUnitEdges);
    BetterConstantPropagator bcp = new BetterConstantPropagator(graph);
    bcp.doAnalysis();
    boolean propagatedThrow = false;
    for (Unit u : body.getUnits()) {
      ConstantState v = bcp.getFlowBefore(u);
      boolean expectsRealValue = false;
      if (u instanceof AssignStmt) {
        AssignStmt assign = ((AssignStmt) u);
        Value rop = assign.getRightOp();
        if (rop instanceof Local) {
          Local l = (Local) rop;
          Constant c = v.getConstant(l);
          if (c != null) {
            assign.setRightOp((Constant) c);
            continue;
          }
        }
        expectsRealValue = expectsRealValue(rop);
      }
      if (u instanceof IfStmt) {
        expectsRealValue = expectsRealValue(((IfStmt) u).getCondition());
      }

      for (ValueBox r : u.getUseBoxes()) {
        if (r instanceof ImmediateBox) {
          Value src = r.getValue();
          if (src instanceof Local) {
            Constant val = v.getConstant((Local) src);
            if (val != null) {
              if (u instanceof ThrowStmt) {
                propagatedThrow = true;
              }
              if (expectsRealValue && !(val instanceof RealConstant)) {
                if (val instanceof IntConstant) {
                  val = FloatConstant.v(((IntConstant) val).value);
                } else if (val instanceof LongConstant) {
                  val = DoubleConstant.v(((LongConstant) val).value);
                }
              }
              r.setValue((Constant) val);
            }
          }
        }

      }
    }
    localPacker.unpack();
    if (propagatedThrow) {
      DexNullThrowTransformer.v().transform(body);
    }
    DexNullArrayRefTransformer.v().transform(body);
  }

  private static boolean expectsRealValue(Value op) {
    return op instanceof CmpgExpr || op instanceof CmplExpr;
  }

  private static class ConstantState {
    BitSet nonConstant = new BitSet();
    Map<Local, Constant> constants = new HashMap<>();

    public Constant getConstant(Local l) {
      Constant r = constants.get(l);
      return r;
    }

    public void setNonConstant(Local l) {
      nonConstant.set(l.getNumber());
      constants.remove(l);
    }

    public void setConstant(Local l, Constant value) {
      if (value == null) {
        throw new IllegalArgumentException("Not valid");
      }
      constants.put(l, value);
      nonConstant.clear(l.getNumber());
    }

    public void copyTo(ConstantState dest) {
      dest.nonConstant = (BitSet) nonConstant.clone();
      dest.constants = new HashMap<>(constants);
      dest.checkConsistency();
      checkConsistency();
    }

    private void checkConsistency() {
      for (Local i : constants.keySet()) {
        if (nonConstant.get(i.getNumber())) {
          throw new IllegalStateException(
              "A local seems to be constant and not at the same time: " + i + " (" + i.getNumber() + ")");
        }
      }
    }

    @Override
    public String toString() {
      return "Non-constants: " + nonConstant + "\nConstants: " + constants;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((constants == null) ? 0 : constants.hashCode());
      result = prime * result + ((nonConstant == null) ? 0 : nonConstant.hashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null) {
        return false;
      }
      if (getClass() != obj.getClass()) {
        return false;
      }
      ConstantState other = (ConstantState) obj;
      if (nonConstant == null) {
        if (other.nonConstant != null) {
          return false;
        }
      } else if (!nonConstant.equals(other.nonConstant)) {
        return false;
      }
      if (constants == null) {
        if (other.constants != null) {
          return false;
        }
      } else if (!constants.equals(other.constants)) {
        return false;
      }
      return true;
    }

    public void merge(ConstantState in1, ConstantState in2) {
      checkConsistency();
      in1.checkConsistency();
      in2.checkConsistency();
      nonConstant.or(in1.nonConstant);
      nonConstant.or(in2.nonConstant);
      if (!in1.constants.isEmpty()) {
        mergeInternal(in1, in2);
      }
      if (!in2.constants.isEmpty()) {
        mergeInternal(in2, in1);
      }
      checkConsistency();
      in1.checkConsistency();
      in2.checkConsistency();
    }

    private void mergeInternal(ConstantState in1, ConstantState in2) {
      for (Entry<Local, Constant> r : in1.constants.entrySet()) {
        Local l = r.getKey();
        if (in2.nonConstant.get(l.getNumber())) {
          setNonConstant(l);
          continue;
        }
        Constant rr = in2.getConstant(l);
        if (rr == null) {
          setConstant(l, r.getValue());
        } else {
          if (rr.equals(r.getValue())) {
            setConstant(l, rr);
          } else {
            setNonConstant(l);
          }
        }
      }
    }

    public void clear() {
      nonConstant.clear();
      constants = new HashMap<>();
    }

    public void mergeInto(ConstantState in) {
      nonConstant.or(in.nonConstant);
      if (!in.constants.isEmpty()) {
        mergeInternal(in, this);
      }
      Iterator<Local> it = constants.keySet().iterator();
      while (it.hasNext()) {
        if (in.nonConstant.get(it.next().getNumber())) {
          it.remove();
        }
      }
      checkConsistency();

    }

  }

  private class BetterConstantPropagator extends ForwardFlowAnalysis<Unit, ConstantState> {

    public BetterConstantPropagator(DirectedGraph<Unit> graph) {
      super(graph);
    }

    @Override
    protected void flowThrough(ConstantState in, Unit d, ConstantState out) {
      in.copyTo(out);
      if (d instanceof Stmt) {
        Stmt s = (Stmt) d;
        if (s instanceof AssignStmt) {
          AssignStmt assign = (AssignStmt) s;
          if (assign.getLeftOp() instanceof Local) {
            Local l = (Local) assign.getLeftOp();
            Object rop = assign.getRightOp();
            Constant value = null;
            if (rop instanceof Constant) {
              value = (Constant) rop;
            } else {
              if (rop instanceof Local) {
                value = in.getConstant((Local) rop);
              }
            }
            if (value == null) {
              out.setNonConstant(l);
            } else {
              out.setConstant(l, value);
            }

          }
        } else if (s instanceof IdentityStmt) {
          out.setNonConstant((Local) ((IdentityStmt) s).getLeftOp());
        }
      }
    }

    @Override
    protected ConstantState newInitialFlow() {
      return new ConstantState();
    }

    @Override
    protected void mergeInto(Unit succNode, ConstantState inout, ConstantState in) {
      inout.mergeInto(in);
    }

    @Override
    protected void merge(ConstantState in1, ConstantState in2, ConstantState out) {
      out.merge(in1, in2);
    }

    @Override
    protected void copy(ConstantState source, ConstantState dest) {
      source.copyTo(dest);
    }

  }

}
