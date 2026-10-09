/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.query;

import static io.vidocq.mansart.jpa.core.query.CriteriaExpressions.*;

import io.vidocq.mansart.jpa.core.query.jpql.Ast;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.*;
import jakarta.persistence.metamodel.Metamodel;
import java.math.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Chapter 6 frontend. All operations lower to the chapter 4 query AST and its existing translator. */
public final class CriteriaBuilderImpl implements CriteriaBuilder {
    final Metamodel metamodel;
    private final AtomicInteger parameterIds = new AtomicInteger();
    public CriteriaBuilderImpl(Metamodel metamodel) { this.metamodel=metamodel; }
    public static <T> jakarta.persistence.TypedQuery<T> query(CriteriaQuery<T> criteria,
            jakarta.persistence.FlushModeType mode,QueryRuntime runtime) {
        var query=(CriteriaQueries.Query<T>) criteria;
        var selection=query.getSelection();
        List<Selection<?>> elements=selection==null?List.of():selection.isCompoundSelection()
            ?selection.getCompoundSelectionItems():List.of(selection);
        return new JpqlQuery<>(query.statement(),query.getResultType(),mode,runtime,query.getParameters(),elements);
    }
    @SuppressWarnings("unchecked") // The set-select wrapper stores its own exact result Class<T>.
    public static <T> jakarta.persistence.TypedQuery<T> query(CriteriaSelect<T> criteria,
            jakarta.persistence.FlushModeType mode,QueryRuntime runtime) {
        if (criteria instanceof CriteriaQuery<?> q) return query((CriteriaQuery<T>) q,mode,runtime);
        var select=(SetSelect<T>) criteria;
        return new JpqlQuery<>(select.statement(),select.resultType(),mode,runtime,select.parameters(),select.elements());
    }
    public static jakarta.persistence.Query query(CriteriaUpdate<?> criteria,
            jakarta.persistence.FlushModeType mode,QueryRuntime runtime) {
        return new JpqlQuery<>(((CriteriaQueries.Statement) criteria).statement(),null,mode,runtime,criteria.getParameters(),List.of());
    }
    public static jakarta.persistence.Query query(CriteriaDelete<?> criteria,
            jakarta.persistence.FlushModeType mode,QueryRuntime runtime) {
        return new JpqlQuery<>(((CriteriaQueries.Statement) criteria).statement(),null,mode,runtime,criteria.getParameters(),List.of());
    }
    @Override public CriteriaQuery<Object> createQuery() { return createQuery(Object.class); }
    @Override public <T> CriteriaQuery<T> createQuery(Class<T> t) { return new CriteriaQueries.Query<>(this,t); }
    @Override public CriteriaQuery<Tuple> createTupleQuery() { return createQuery(Tuple.class); }
    @Override public <T> CriteriaUpdate<T> createCriteriaUpdate(Class<T> t) { return new CriteriaQueries.Update<>(this,t); }
    @Override public <T> CriteriaDelete<T> createCriteriaDelete(Class<T> t) { return new CriteriaQueries.Delete<>(this,t); }
    @Override public <Y> CompoundSelection<Y> construct(Class<Y> t,Selection<?>... s) { return new Compound<>(t,Arrays.asList(s)); }
    @Override public CompoundSelection<Tuple> tuple(Selection<?>... s) { return tuple(Arrays.asList(s)); }
    @Override public CompoundSelection<Tuple> tuple(List<Selection<?>> s) { return new Compound<>(Tuple.class,s); }
    @Override public CompoundSelection<Object[]> array(Selection<?>... s) { return array(Arrays.asList(s)); }
    @Override public CompoundSelection<Object[]> array(List<Selection<?>> s) { return new Compound<>(Object[].class,s); }
    @Override public Order asc(Expression<?> e) { return asc(e,Nulls.NONE); }
    @Override public Order desc(Expression<?> e) { return desc(e,Nulls.NONE); }
    @Override public Order asc(Expression<?> e,Nulls n) { return new Ordering(e,true,n); }
    @Override public Order desc(Expression<?> e,Nulls n) { return new Ordering(e,false,n); }
    @SuppressWarnings("unchecked") // Numeric/function result follows the API's input expression type.
    private <T> Class<T> type(Expression<? extends T> e) { return (Class<T>) e.getJavaType(); }
    private <T> Expression<T> function(String name,Class<T> t,Object... values) {
        return new Node<>(t,() -> new Ast.Function(name,Arrays.stream(values).map(CriteriaExpressions::ast).toList()),children(values));
    }
    @Override public <T> Expression<T> function(String name,Class<T> t,Expression<?>... e) { return function(name,t,(Object[]) e); }
    private <T> Expression<T> aggregate(String name,Class<T> t,Expression<?> e,boolean distinct) {
        return new Node<>(t,() -> new Ast.Aggregate(name,distinct,ast(e)),children(e));
    }
    @Override public <N extends Number> Expression<Double> avg(Expression<N> e) { return aggregate("AVG",Double.class,e,false); }
    @Override public <N extends Number> Expression<N> sum(Expression<N> e) { return aggregate("SUM",type(e),e,false); }
    @Override public Expression<Long> sumAsLong(Expression<Integer> e) { return aggregate("SUM",Long.class,e,false); }
    @Override public Expression<Double> sumAsDouble(Expression<Float> e) { return aggregate("SUM",Double.class,e,false); }
    @Override public <N extends Number> Expression<N> max(Expression<N> e) { return aggregate("MAX",type(e),e,false); }
    @Override public <N extends Number> Expression<N> min(Expression<N> e) { return aggregate("MIN",type(e),e,false); }
    @Override public <X extends Comparable<? super X>> Expression<X> greatest(Expression<X> e) { return aggregate("MAX",type(e),e,false); }
    @Override public <X extends Comparable<? super X>> Expression<X> least(Expression<X> e) { return aggregate("MIN",type(e),e,false); }
    @Override public Expression<Long> count(Expression<?> e) { return aggregate("COUNT",Long.class,e,false); }
    @Override public Expression<Long> countDistinct(Expression<?> e) { return aggregate("COUNT",Long.class,e,true); }
    @Override public Predicate exists(Subquery<?> s) { return new Pred(() -> new Ast.Exists(((CriteriaQueries.Sub<?>) s).state.ast(true),false),children(s)); }
    @Override public <Y> Expression<Y> all(Subquery<Y> s) { return function("ALL",type(s),s); }
    @Override public <Y> Expression<Y> some(Subquery<Y> s) { return function("ANY",type(s),s); }
    @Override public <Y> Expression<Y> any(Subquery<Y> s) { return function("ANY",type(s),s); }
    private Predicate junction(boolean and,List<Expression<Boolean>> expressions) {
        List<Expression<Boolean>> copy=List.copyOf(expressions);
        return new Pred(() -> {
            Ast.Expr result=new Ast.Literal(and);
            for (Expression<Boolean> e:copy) result=new Ast.Binary(result,and?Ast.Op.AND:Ast.Op.OR,ast(e));
            return result;
        },copy.stream().map(CriteriaExpressions::node).toList(),and?Predicate.BooleanOperator.AND:Predicate.BooleanOperator.OR,copy,false);
    }
    Predicate and(Expression<Boolean> e) { return e instanceof Predicate p?p:junction(true,List.of(e)); }
    @Override public Predicate and(Expression<Boolean> a,Expression<Boolean> b) { return junction(true,List.of(a,b)); }
    @Override public Predicate and(Predicate... p) { return and(Arrays.asList(p)); }
    @Override public Predicate and(List<Predicate> p) { return junction(true,new ArrayList<>(p)); }
    @Override public Predicate or(Expression<Boolean> a,Expression<Boolean> b) { return junction(false,List.of(a,b)); }
    @Override public Predicate or(Predicate... p) { return or(Arrays.asList(p)); }
    @Override public Predicate or(List<Predicate> p) { return junction(false,new ArrayList<>(p)); }
    @Override public Predicate not(Expression<Boolean> e) { return and(e).not(); }
    @Override public Predicate conjunction() { return and(new Predicate[0]); }
    @Override public Predicate disjunction() { return or(new Predicate[0]); }
    @Override public Predicate isTrue(Expression<Boolean> e) { return equal(e,true); }
    @Override public Predicate isFalse(Expression<Boolean> e) { return equal(e,false); }
    @Override public Predicate isNull(Expression<?> e) { return e.isNull(); }
    @Override public Predicate isNotNull(Expression<?> e) { return e.isNotNull(); }
    @Override public Predicate equal(Expression<?> a,Expression<?> b) { return compare(Ast.Op.EQ,a,b); }
    @Override public Predicate equal(Expression<?> a,Object b) { return compare(Ast.Op.EQ,a,b); }
    @Override public Predicate notEqual(Expression<?> a,Expression<?> b) { return compare(Ast.Op.NE,a,b); }
    @Override public Predicate notEqual(Expression<?> a,Object b) { return compare(Ast.Op.NE,a,b); }
    @Override public <Y extends Comparable<? super Y>> Predicate greaterThan(Expression<? extends Y> a,Expression<? extends Y> b) { return compare(Ast.Op.GT,a,b); }
    @Override public <Y extends Comparable<? super Y>> Predicate greaterThan(Expression<? extends Y> a,Y b) { return compare(Ast.Op.GT,a,b); }
    @Override public <Y extends Comparable<? super Y>> Predicate greaterThanOrEqualTo(Expression<? extends Y> a,Expression<? extends Y> b) { return compare(Ast.Op.GE,a,b); }
    @Override public <Y extends Comparable<? super Y>> Predicate greaterThanOrEqualTo(Expression<? extends Y> a,Y b) { return compare(Ast.Op.GE,a,b); }
    @Override public <Y extends Comparable<? super Y>> Predicate lessThan(Expression<? extends Y> a,Expression<? extends Y> b) { return compare(Ast.Op.LT,a,b); }
    @Override public <Y extends Comparable<? super Y>> Predicate lessThan(Expression<? extends Y> a,Y b) { return compare(Ast.Op.LT,a,b); }
    @Override public <Y extends Comparable<? super Y>> Predicate lessThanOrEqualTo(Expression<? extends Y> a,Expression<? extends Y> b) { return compare(Ast.Op.LE,a,b); }
    @Override public <Y extends Comparable<? super Y>> Predicate lessThanOrEqualTo(Expression<? extends Y> a,Y b) { return compare(Ast.Op.LE,a,b); }
    private Predicate betweenValues(Object a,Object low,Object high) { return new Pred(() -> new Ast.Between(ast(a),ast(low),ast(high),false),children(a,low,high)); }
    @Override public <Y extends Comparable<? super Y>> Predicate between(Expression<? extends Y> a,Expression<? extends Y> l,Expression<? extends Y> h) { return betweenValues(a,l,h); }
    @Override public <Y extends Comparable<? super Y>> Predicate between(Expression<? extends Y> a,Y l,Y h) { return betweenValues(a,l,h); }
    @Override public Predicate gt(Expression<? extends Number> a,Expression<? extends Number> b) { return compare(Ast.Op.GT,a,b); }
    @Override public Predicate gt(Expression<? extends Number> a,Number b) { return compare(Ast.Op.GT,a,b); }
    @Override public Predicate ge(Expression<? extends Number> a,Expression<? extends Number> b) { return compare(Ast.Op.GE,a,b); }
    @Override public Predicate ge(Expression<? extends Number> a,Number b) { return compare(Ast.Op.GE,a,b); }
    @Override public Predicate lt(Expression<? extends Number> a,Expression<? extends Number> b) { return compare(Ast.Op.LT,a,b); }
    @Override public Predicate lt(Expression<? extends Number> a,Number b) { return compare(Ast.Op.LT,a,b); }
    @Override public Predicate le(Expression<? extends Number> a,Expression<? extends Number> b) { return compare(Ast.Op.LE,a,b); }
    @Override public Predicate le(Expression<? extends Number> a,Number b) { return compare(Ast.Op.LE,a,b); }
    @Override public Expression<Integer> sign(Expression<? extends Number> e) { return function("SIGN",Integer.class,e); }
    @Override public <N extends Number> Expression<N> neg(Expression<N> e) { return new Node<>(type(e),() -> new Ast.Negate(ast(e)),children(e)); }
    @Override public <N extends Number> Expression<N> abs(Expression<N> e) { return function("ABS",type(e),e); }
    @Override public <N extends Number> Expression<N> ceiling(Expression<N> e) { return function("CEILING",type(e),e); }
    @Override public <N extends Number> Expression<N> floor(Expression<N> e) { return function("FLOOR",type(e),e); }
    @SuppressWarnings("unchecked") // §6.5.7 numeric promotion determines the expression's runtime class, even for Number-typed inputs.
    private <N> Expression<N> arithmetic(Ast.Op op,Class<N> t,Object a,Object b) {
        Class<?> left=a instanceof Expression<?> e?e.getJavaType():a.getClass();
        Class<?> right=b instanceof Expression<?> e?e.getJavaType():b.getClass();
        Class<?> promoted=Translator.promote(op,left,right);
        return new Node<>(promoted==null?t:(Class<N>) promoted,() -> new Ast.Binary(ast(a),op,ast(b)),children(a,b));
    }
    @Override public <N extends Number> Expression<N> sum(Expression<? extends N> a,Expression<? extends N> b) { return arithmetic(Ast.Op.PLUS,type(a),a,b); }
    @Override public <N extends Number> Expression<N> sum(Expression<? extends N> a,N b) { return arithmetic(Ast.Op.PLUS,type(a),a,b); }
    @Override public <N extends Number> Expression<N> sum(N a,Expression<? extends N> b) { return arithmetic(Ast.Op.PLUS,type(b),a,b); }
    @Override public <N extends Number> Expression<N> prod(Expression<? extends N> a,Expression<? extends N> b) { return arithmetic(Ast.Op.TIMES,type(a),a,b); }
    @Override public <N extends Number> Expression<N> prod(Expression<? extends N> a,N b) { return arithmetic(Ast.Op.TIMES,type(a),a,b); }
    @Override public <N extends Number> Expression<N> prod(N a,Expression<? extends N> b) { return arithmetic(Ast.Op.TIMES,type(b),a,b); }
    @Override public <N extends Number> Expression<N> diff(Expression<? extends N> a,Expression<? extends N> b) { return arithmetic(Ast.Op.MINUS,type(a),a,b); }
    @Override public <N extends Number> Expression<N> diff(Expression<? extends N> a,N b) { return arithmetic(Ast.Op.MINUS,type(a),a,b); }
    @Override public <N extends Number> Expression<N> diff(N a,Expression<? extends N> b) { return arithmetic(Ast.Op.MINUS,type(b),a,b); }
    @Override public Expression<Number> quot(Expression<? extends Number> a,Expression<? extends Number> b) { return arithmetic(Ast.Op.DIVIDE,Number.class,a,b); }
    @Override public Expression<Number> quot(Expression<? extends Number> a,Number b) { return arithmetic(Ast.Op.DIVIDE,Number.class,a,b); }
    @Override public Expression<Number> quot(Number a,Expression<? extends Number> b) { return arithmetic(Ast.Op.DIVIDE,Number.class,a,b); }
    @Override public Expression<Integer> mod(Expression<Integer> a,Expression<Integer> b) { return function("MOD",Integer.class,a,b); }
    @Override public Expression<Integer> mod(Expression<Integer> a,Integer b) { return function("MOD",Integer.class,a,b); }
    @Override public Expression<Integer> mod(Integer a,Expression<Integer> b) { return function("MOD",Integer.class,a,b); }
    @Override public Expression<Double> sqrt(Expression<? extends Number> e) { return function("SQRT",Double.class,e); }
    @Override public Expression<Double> exp(Expression<? extends Number> e) { return function("EXP",Double.class,e); }
    @Override public Expression<Double> ln(Expression<? extends Number> e) { return function("LN",Double.class,e); }
    @Override public Expression<Double> power(Expression<? extends Number> a,Expression<? extends Number> b) { return function("POWER",Double.class,a,b); }
    @Override public Expression<Double> power(Expression<? extends Number> a,Number b) { return function("POWER",Double.class,a,b); }
    @Override public <T extends Number> Expression<T> round(Expression<T> a,Integer b) { return function("ROUND",type(a),a,b); }
    @Override public Expression<Long> toLong(Expression<? extends Number> e) { return e.as(Long.class); }
    @Override public Expression<Integer> toInteger(Expression<? extends Number> e) { return e.as(Integer.class); }
    @Override public Expression<Float> toFloat(Expression<? extends Number> e) { return e.as(Float.class); }
    @Override public Expression<Double> toDouble(Expression<? extends Number> e) { return e.as(Double.class); }
    @Override public Expression<BigDecimal> toBigDecimal(Expression<? extends Number> e) { return e.as(BigDecimal.class); }
    @Override public Expression<BigInteger> toBigInteger(Expression<? extends Number> e) { return e.as(BigInteger.class); }
    @Override public Expression<String> toString(Expression<Character> e) { return e.cast(String.class); }
    @SuppressWarnings("unchecked") // Literal's type token is its own runtime class.
    @Override public <T> Expression<T> literal(T value) {
        if (value == null) throw new IllegalArgumentException("Use nullLiteral for null");
        return new Node<>((Class<T>) value.getClass(),new Ast.Literal(value));
    }
    @Override public <T> Expression<T> nullLiteral(Class<T> t) { return new Node<>(t,new Ast.Literal(null)); }
    @Override public <T> ParameterExpression<T> parameter(Class<T> t) { return parameter(t,null); }
    @Override public <T> ParameterExpression<T> parameter(Class<T> t,String name) { return new Param<>(t,name,parameterIds.incrementAndGet()); }
    @Override public <C extends Collection<?>> Predicate isEmpty(Expression<C> c) { return new Pred(() -> new Ast.IsEmpty(ast(c),false),children(c)); }
    @Override public <C extends Collection<?>> Predicate isNotEmpty(Expression<C> c) { return new Pred(() -> new Ast.IsEmpty(ast(c),true),children(c)); }
    @Override public <C extends Collection<?>> Expression<Integer> size(Expression<C> c) { return function("SIZE",Integer.class,c); }
    @Override public <C extends Collection<?>> Expression<Integer> size(C c) { return literal(c.size()); }
    private Predicate member(Object e,Object c,boolean not) { return new Pred(() -> new Ast.MemberOf(ast(e),ast(c),not),children(e,c)); }
    @Override public <E,C extends Collection<E>> Predicate isMember(Expression<E> e,Expression<C> c) { return member(e,c,false); }
    @Override public <E,C extends Collection<E>> Predicate isMember(E e,Expression<C> c) { return member(e,c,false); }
    @Override public <E,C extends Collection<E>> Predicate isNotMember(Expression<E> e,Expression<C> c) { return member(e,c,true); }
    @Override public <E,C extends Collection<E>> Predicate isNotMember(E e,Expression<C> c) { return member(e,c,true); }
    @SuppressWarnings("unchecked") // Java collection class tokens do not retain their element type.
    @Override public <V,M extends Map<?,V>> Expression<Collection<V>> values(M m) { return new Node<>((Class<Collection<V>>) (Class<?>) Collection.class,new Ast.Literal(m.values())); }
    @SuppressWarnings("unchecked") // Java collection class tokens do not retain their element type.
    @Override public <K,M extends Map<K,?>> Expression<Set<K>> keys(M m) { return new Node<>((Class<Set<K>>) (Class<?>) Set.class,new Ast.Literal(m.keySet())); }
    private Predicate like(Object a,Object b,Object escape,boolean not) { return new Pred(() -> new Ast.Like(ast(a),ast(b),escape==null?null:ast(escape),not),children(a,b,escape)); }
    @Override public Predicate like(Expression<String> a,Expression<String> b) { return like(a,b,null,false); }
    @Override public Predicate like(Expression<String> a,String b) { return like(a,b,null,false); }
    @Override public Predicate like(Expression<String> a,Expression<String> b,Expression<Character> e) { return like(a,b,e,false); }
    @Override public Predicate like(Expression<String> a,Expression<String> b,char e) { return like(a,b,e,false); }
    @Override public Predicate like(Expression<String> a,String b,Expression<Character> e) { return like(a,b,e,false); }
    @Override public Predicate like(Expression<String> a,String b,char e) { return like(a,b,e,false); }
    @Override public Predicate notLike(Expression<String> a,Expression<String> b) { return like(a,b,null,true); }
    @Override public Predicate notLike(Expression<String> a,String b) { return like(a,b,null,true); }
    @Override public Predicate notLike(Expression<String> a,Expression<String> b,Expression<Character> e) { return like(a,b,e,true); }
    @Override public Predicate notLike(Expression<String> a,Expression<String> b,char e) { return like(a,b,e,true); }
    @Override public Predicate notLike(Expression<String> a,String b,Expression<Character> e) { return like(a,b,e,true); }
    @Override public Predicate notLike(Expression<String> a,String b,char e) { return like(a,b,e,true); }
    @Override public Expression<String> concat(List<Expression<String>> e) { return function("CONCAT",String.class,e.toArray()); }
    @Override public Expression<String> concat(Expression<String> a,Expression<String> b) { return function("CONCAT",String.class,a,b); }
    @Override public Expression<String> concat(Expression<String> a,String b) { return function("CONCAT",String.class,a,b); }
    @Override public Expression<String> concat(String a,Expression<String> b) { return function("CONCAT",String.class,a,b); }
    @Override public Expression<String> substring(Expression<String> a,Expression<Integer> start) { return function("SUBSTRING",String.class,a,start); }
    @Override public Expression<String> substring(Expression<String> a,int start) { return function("SUBSTRING",String.class,a,start); }
    @Override public Expression<String> substring(Expression<String> a,Expression<Integer> start,Expression<Integer> len) { return function("SUBSTRING",String.class,a,start,len); }
    @Override public Expression<String> substring(Expression<String> a,int start,int len) { return function("SUBSTRING",String.class,a,start,len); }
    @Override public Expression<String> trim(Expression<String> e) { return trim(Trimspec.BOTH,' ',e); }
    @Override public Expression<String> trim(Trimspec spec,Expression<String> e) { return trim(spec,' ',e); }
    @Override public Expression<String> trim(Expression<Character> c,Expression<String> e) { return trim(Trimspec.BOTH,c,e); }
    @Override public Expression<String> trim(char c,Expression<String> e) { return trim(Trimspec.BOTH,c,e); }
    @Override public Expression<String> trim(Trimspec spec,Expression<Character> c,Expression<String> e) { return function("TRIM",String.class,spec.name(),c,e); }
    @Override public Expression<String> trim(Trimspec spec,char c,Expression<String> e) { return function("TRIM",String.class,spec.name(),c,e); }
    @Override public Expression<String> lower(Expression<String> e) { return function("LOWER",String.class,e); }
    @Override public Expression<String> upper(Expression<String> e) { return function("UPPER",String.class,e); }
    @Override public Expression<Integer> length(Expression<String> e) { return function("LENGTH",Integer.class,e); }
    @Override public Expression<Integer> locate(Expression<String> a,Expression<String> b) { return function("LOCATE",Integer.class,b,a); }
    @Override public Expression<Integer> locate(Expression<String> a,String b) { return function("LOCATE",Integer.class,b,a); }
    @Override public Expression<Integer> locate(Expression<String> a,Expression<String> b,Expression<Integer> start) { return function("LOCATE",Integer.class,b,a,start); }
    @Override public Expression<Integer> locate(Expression<String> a,String b,int start) { return function("LOCATE",Integer.class,b,a,start); }
    @Override public Expression<String> replace(Expression<String> a,Expression<String> b,Expression<String> c) { return function("REPLACE",String.class,a,b,c); }
    @Override public Expression<String> replace(Expression<String> a,String b,Expression<String> c) { return function("REPLACE",String.class,a,b,c); }
    @Override public Expression<String> replace(Expression<String> a,Expression<String> b,String c) { return function("REPLACE",String.class,a,b,c); }
    @Override public Expression<String> replace(Expression<String> a,String b,String c) { return function("REPLACE",String.class,a,b,c); }
    @Override public Expression<String> left(Expression<String> a,int n) { return function("LEFT",String.class,a,n); }
    @Override public Expression<String> left(Expression<String> a,Expression<Integer> n) { return function("LEFT",String.class,a,n); }
    @Override public Expression<String> right(Expression<String> a,int n) { return function("RIGHT",String.class,a,n); }
    @Override public Expression<String> right(Expression<String> a,Expression<Integer> n) { return function("RIGHT",String.class,a,n); }
    @Override public Expression<java.sql.Date> currentDate() { return function("CURRENT_DATE",java.sql.Date.class); }
    @Override public Expression<java.sql.Time> currentTime() { return function("CURRENT_TIME",java.sql.Time.class); }
    @Override public Expression<java.sql.Timestamp> currentTimestamp() { return function("CURRENT_TIMESTAMP",java.sql.Timestamp.class); }
    @Override public Expression<java.time.LocalDate> localDate() { return function("LOCAL_DATE",java.time.LocalDate.class); }
    @Override public Expression<java.time.LocalDateTime> localDateTime() { return function("LOCAL_DATETIME",java.time.LocalDateTime.class); }
    @Override public Expression<java.time.LocalTime> localTime() { return function("LOCAL_TIME",java.time.LocalTime.class); }
    @SuppressWarnings("unchecked") // TemporalField's public type parameters carry its numeric result type.
    @Override public <N,T extends java.time.temporal.Temporal> Expression<N> extract(TemporalField<N,T> field,Expression<T> value) {
        String name=field.toString().toUpperCase(Locale.ROOT);
        Class<?> result=switch (name) {
            case "SECOND" -> Double.class;
            case "DATE" -> java.time.LocalDate.class;
            case "TIME" -> java.time.LocalTime.class;
            case "YEAR", "QUARTER", "MONTH", "WEEK", "DAY", "HOUR", "MINUTE" -> Integer.class;
            default -> throw new IllegalArgumentException("Unsupported temporal field: "+field);
        };
        return function("EXTRACT",(Class<N>) result,name,value);
    }
    @Override public <T> In<T> in(Expression<? extends T> value) { return new InNode<>(value); }
    @Override public <Y> Expression<Y> coalesce(Expression<? extends Y> a,Expression<? extends Y> b) { return function("COALESCE",type(a),a,b); }
    @Override public <Y> Expression<Y> coalesce(Expression<? extends Y> a,Y b) { return function("COALESCE",type(a),a,b); }
    @Override public <Y> Expression<Y> nullif(Expression<Y> a,Expression<?> b) { return function("NULLIF",type(a),a,b); }
    @Override public <Y> Expression<Y> nullif(Expression<Y> a,Y b) { return function("NULLIF",type(a),a,b); }
    @Override public <T> Coalesce<T> coalesce() { return new CoalesceNode<>(); }
    @Override public <C,R> SimpleCase<C,R> selectCase(Expression<? extends C> e) { return new SimpleCaseNode<>(e); }
    @Override public <R> Case<R> selectCase() { return new CaseNode<>(); }

    private static final class InNode<T> extends Pred implements In<T> {
        final Expression<? extends T> operand;
        final List<Object> values=new ArrayList<>();
        InNode(Expression<? extends T> operand) { super(() -> new Ast.Literal(false),new ArrayList<>(children(operand))); this.operand=operand; }
        @Override Ast.Expr ast() { return new Ast.In(CriteriaExpressions.ast(operand),values.stream().map(CriteriaExpressions::ast).toList(),false); }
        @SuppressWarnings("unchecked") // In's operand is covariant in its declared T.
        @Override public Expression<T> getExpression() { return (Expression<T>) operand; }
        @Override public In<T> value(T v) { values.add(v); return this; }
        @Override public In<T> value(Expression<? extends T> v) { values.add(v); children.add(node(v)); return this; }
    }
    private static final class CoalesceNode<T> extends Node<T> implements Coalesce<T> {
        final List<Object> values=new ArrayList<>();
        @SuppressWarnings("unchecked") // Empty coalesce has no type until an operand is supplied.
        CoalesceNode() { super((Class<T>) Object.class,new Ast.Literal(null)); }
        @Override public Class<? extends T> getJavaType() { return resultType(values); }
        @Override Ast.Expr ast() { return new Ast.Function("COALESCE",values.stream().map(CriteriaExpressions::ast).toList()); }
        @Override public Coalesce<T> value(T v) { values.add(v); return this; }
        @Override public Coalesce<T> value(Expression<? extends T> v) { values.add(v); return this; }
        @Override void parameters(Set<ParameterExpression<?>> p) { values.forEach(v -> { if (v instanceof Node<?> n) n.parameters(p); }); }
    }
    private record Arm(Object condition,Object result) {
        Ast.When ast() { return new Ast.When(CriteriaExpressions.ast(condition),CriteriaExpressions.ast(result)); }
    }
    @SuppressWarnings("unchecked") // CASE/COALESCE infer their result token from the supplied result values.
    private static <T> Class<T> resultType(List<?> results) {
        Class<?> result=Object.class;
        for (Object value:results) {
            if (value==null) continue;
            Class<?> next=value instanceof Expression<?> e?e.getJavaType():value.getClass();
            if (result==Object.class) result=next;
            else if (Number.class.isAssignableFrom(result) && Number.class.isAssignableFrom(next))
                result=Translator.promote(Ast.Op.PLUS,result,next);
            else if (!result.isAssignableFrom(next)) {
                while (!result.isAssignableFrom(next) && result.getSuperclass()!=null) result=result.getSuperclass();
            }
        }
        return (Class<T>) result;
    }
    private static class CaseNode<R> extends Node<R> implements Case<R> {
        final List<Arm> whens=new ArrayList<>();
        final List<Node<?>> dependencies=new ArrayList<>();
        Object otherwise;
        @SuppressWarnings("unchecked") // CASE result is determined by its result arms; no arm exists initially.
        CaseNode() { super((Class<R>) Object.class,new Ast.Literal(null)); }
        @Override Ast.Expr ast() { return new Ast.Case(null,whens.stream().map(Arm::ast).toList(),CriteriaExpressions.ast(otherwise)); }
        @Override public Class<? extends R> getJavaType() {
            var values=new ArrayList<>(whens.stream().map(Arm::result).toList()); values.add(otherwise); return resultType(values);
        }
        @Override public Case<R> when(Expression<Boolean> c,R r) { add(c,r); return this; }
        @Override public Case<R> when(Expression<Boolean> c,Expression<? extends R> r) { add(c,r); return this; }
        void add(Object c,Object r) { whens.add(new Arm(c,r)); dependencies.addAll(children(c,r)); }
        @Override public Expression<R> otherwise(R r) { otherwise=r; return this; }
        @Override public Expression<R> otherwise(Expression<? extends R> r) { otherwise=r; return this; }
        @Override void parameters(Set<ParameterExpression<?>> p) { dependencies.forEach(n -> n.parameters(p)); if (otherwise instanceof Node<?> n) n.parameters(p); }
    }
    private static final class SimpleCaseNode<C,R> extends Node<R> implements SimpleCase<C,R> {
        final Expression<? extends C> operand;
        final List<Arm> whens=new ArrayList<>();
        final List<Node<?>> dependencies=new ArrayList<>();
        Object otherwise;
        @SuppressWarnings("unchecked") // Simple CASE result has no reified token before its first result arm.
        SimpleCaseNode(Expression<? extends C> operand) { super((Class<R>) Object.class,new Ast.Literal(null)); this.operand=operand; }
        @Override Ast.Expr ast() { return new Ast.Case(CriteriaExpressions.ast(operand),whens.stream().map(Arm::ast).toList(),CriteriaExpressions.ast(otherwise)); }
        @Override public Class<? extends R> getJavaType() {
            var values=new ArrayList<>(whens.stream().map(Arm::result).toList()); values.add(otherwise); return resultType(values);
        }
        @Override public Expression<C> getExpression() { return operand.as(operandType()); }
        @SuppressWarnings("unchecked") // SimpleCase operand is covariant in C.
        private Class<C> operandType() { return (Class<C>) operand.getJavaType(); }
        @Override public SimpleCase<C,R> when(C c,R r) { add(c,r); return this; }
        @Override public SimpleCase<C,R> when(C c,Expression<? extends R> r) { add(c,r); return this; }
        @Override public SimpleCase<C,R> when(Expression<? extends C> c,R r) { add(c,r); return this; }
        @Override public SimpleCase<C,R> when(Expression<? extends C> c,Expression<? extends R> r) { add(c,r); return this; }
        void add(Object c,Object r) { whens.add(new Arm(c,r)); dependencies.addAll(children(c,r)); }
        @Override public Expression<R> otherwise(R r) { otherwise=r; return this; }
        @Override public Expression<R> otherwise(Expression<? extends R> r) { otherwise=r; return this; }
        @Override void parameters(Set<ParameterExpression<?>> p) { node(operand).parameters(p); dependencies.forEach(n -> n.parameters(p)); if (otherwise instanceof Node<?> n) n.parameters(p); }
    }
    @SuppressWarnings("unchecked") // TREAT narrows the same path's model to a verified entity subtype; casts are localized here.
    private <P> P treated(Object path,Class<?> type) {
        var original=(CriteriaExpressions.PathNode<?>) path;
        if (!original.getJavaType().isAssignableFrom(type)) throw new IllegalArgumentException("Not a subtype");
        metamodel.entity(type);
        CriteriaExpressions.PathNode<?> copy;
        if (original instanceof CriteriaFrom.RootNode<?> root) {
            copy = new CriteriaFrom.RootNode<>(metamodel.entity(type), root.state, root.variable);
        } else if (original instanceof CriteriaFrom.JoinNode<?,?> join) {
            var joined = join.correlatedCopy(join.state);
            joined.correlation = null;
            copy = joined;
        } else {
            copy = original.copy();
        }
        copy.treatType = type;
        if (original instanceof CriteriaFrom.FromNode<?,?> from && copy instanceof CriteriaFrom.FromNode<?,?> view)
            from.treatedViews.add(view);
        return (P) copy;
    }
    @Override public <X,T,V extends T> Join<X,V> treat(Join<X,T> p,Class<V> t) { return treated(p,t); }
    @Override public <X,T,E extends T> CollectionJoin<X,E> treat(CollectionJoin<X,T> p,Class<E> t) { return treated(p,t); }
    @Override public <X,T,E extends T> SetJoin<X,E> treat(SetJoin<X,T> p,Class<E> t) { return treated(p,t); }
    @Override public <X,T,E extends T> ListJoin<X,E> treat(ListJoin<X,T> p,Class<E> t) { return treated(p,t); }
    @Override public <X,K,T,V extends T> MapJoin<X,K,V> treat(MapJoin<X,K,T> p,Class<V> t) { return treated(p,t); }
    @Override public <X,T extends X> Path<T> treat(Path<X> p,Class<T> t) { return treated(p,t); }
    @Override public <X,T extends X> Root<T> treat(Root<X> p,Class<T> t) { return treated(p,t); }
    private record SetSelect<T>(Ast.Query query,Class<T> resultType,Set<ParameterExpression<?>> parameters,
            List<Selection<?>> elements) implements CriteriaSelect<T>,CriteriaQueries.Statement {
        @Override public Ast.Statement statement() { return query; }
    }
    @SuppressWarnings("unchecked") // Set operations preserve the first operand's declared result type.
    private <T> CriteriaSelect<T> set(CriteriaSelect<?> a,CriteriaSelect<?> b,Ast.SetOperator op,boolean all) {
        Ast.Query left=(Ast.Query) ((CriteriaQueries.Statement) a).statement(), right=(Ast.Query) ((CriteriaQueries.Statement) b).statement();
        var operands=new ArrayList<Ast.Select>(); var operations=new ArrayList<Ast.SetOperation>();
        if (left instanceof Ast.SetQuery s) { operands.addAll(s.operands()); operations.addAll(s.operations()); } else operands.add((Ast.Select) left);
        operations.add(new Ast.SetOperation(op,all));
        if (right instanceof Ast.SetQuery s) { operands.addAll(s.operands()); operations.addAll(s.operations()); } else operands.add((Ast.Select) right);
        Class<?> type=a instanceof CriteriaQuery<?> q?q.getResultType():((SetSelect<?>) a).resultType;
        Set<ParameterExpression<?>> parameters=new LinkedHashSet<>(a instanceof CriteriaQuery<?> q?q.getParameters():((SetSelect<?>) a).parameters);
        parameters.addAll(b instanceof CriteriaQuery<?> q?q.getParameters():((SetSelect<?>) b).parameters);
        Selection<?> selection=a instanceof CriteriaQuery<?> q?q.getSelection():null;
        List<Selection<?>> elements=selection==null?a instanceof SetSelect<?> s?s.elements:List.of()
            :selection.isCompoundSelection()?selection.getCompoundSelectionItems():List.of(selection);
        return new SetSelect<>(new Ast.SetQuery(operands,operations,List.of()),(Class<T>) type,Set.copyOf(parameters),List.copyOf(elements));
    }
    @Override public <T> CriteriaSelect<T> union(CriteriaSelect<? extends T> a,CriteriaSelect<? extends T> b) { return set(a,b,Ast.SetOperator.UNION,false); }
    @Override public <T> CriteriaSelect<T> unionAll(CriteriaSelect<? extends T> a,CriteriaSelect<? extends T> b) { return set(a,b,Ast.SetOperator.UNION,true); }
    @Override public <T> CriteriaSelect<T> intersect(CriteriaSelect<? super T> a,CriteriaSelect<? super T> b) { return set(a,b,Ast.SetOperator.INTERSECT,false); }
    @Override public <T> CriteriaSelect<T> intersectAll(CriteriaSelect<? super T> a,CriteriaSelect<? super T> b) { return set(a,b,Ast.SetOperator.INTERSECT,true); }
    @Override public <T> CriteriaSelect<T> except(CriteriaSelect<T> a,CriteriaSelect<?> b) { return set(a,b,Ast.SetOperator.EXCEPT,false); }
    @Override public <T> CriteriaSelect<T> exceptAll(CriteriaSelect<T> a,CriteriaSelect<?> b) { return set(a,b,Ast.SetOperator.EXCEPT,true); }
}
