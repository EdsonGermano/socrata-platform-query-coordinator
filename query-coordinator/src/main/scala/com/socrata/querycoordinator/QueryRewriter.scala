package com.socrata.querycoordinator

import com.socrata.querycoordinator.QueryRewriter._
import com.socrata.soql.collection.OrderedMap
import com.socrata.soql.environment.{ColumnName, TableName}
import com.socrata.soql.exceptions.SoQLException
import com.socrata.soql.functions.SoQLFunctions._
import com.socrata.soql.functions._
import com.socrata.soql.typed.{ColumnRef => _, FunctionCall => _, OrderBy => _, _}
import com.socrata.soql.types._
import com.socrata.soql.{SoQLAnalysis, SoQLAnalyzer, typed}
import org.joda.time.{DateTimeConstants, LocalDateTime}

import scala.util.parsing.input.NoPosition
import scala.util.{Failure, Success, Try}

class QueryRewriter(analyzer: SoQLAnalyzer[SoQLType]) {

  import com.socrata.querycoordinator.util.Join._

  private val log = org.slf4j.LoggerFactory.getLogger(classOf[QueryRewriter])

  def ensure(expr: Boolean, msg: String): Option[String] = if (!expr) Some(msg) else None

  // TODO the secondary should probably just give us the names of the columns when we ask about the rollups
  // instead of assuming.
  private[querycoordinator] def rollupColumnId(idx: Int): String = "c" + (idx + 1)

  /** Maps the rollup column expression to the 0 based index in the rollup table.  If we have
    * multiple columns with the same definition, that is fine but we will only use one.
    */
  def rewriteSelection(q: Selection, r: Selection, rollupColIdx: Map[Expr, Int]): Option[Selection] = {
    val mapped: OrderedMap[ColumnName, Option[Expr]] = q.mapValues(e => rewriteExpr(e, rollupColIdx))

    if (mapped.values.forall(c => c.isDefined)) {
      Some(mapped.mapValues { v => v.get })
    } else {
      None
    }
  }

  private def removeAggregates(opt: Option[Seq[OrderBy]]): Option[Seq[OrderBy]] = {
    opt.map { seq =>
      seq.map(o => o.copy(expression = removeAggregates(o.expression)))
    }
  }

  /**
    * Used to remove aggregate functions from the expression in the case where the rollup and query groupings match
    * exactly.
    */
  private def removeAggregates(e: Expr): Expr = {
    e match {
      // If we have reached this point in the rewrite and have mapped things, I think all aggregate functions can
      // only have one argument.  We don't have any multi argument ones right now though so that is hard to test.
      // Given that, falling back to not rewriting and probably generating a query that fails to parse and errors
      // out seems better than throwing away other arguments and returning wrong results if we do need to consider them.
      case fc: FunctionCall if fc.function.isAggregate && fc.parameters.size == 1 =>
        fc.parameters.head
      case fc: FunctionCall =>
        // recurse in case we have a function on top of an aggregation
        fc.copy(parameters = fc.parameters.map(p => removeAggregates(p)))
      case _ => e
    }
  }

  def rewriteGroupBy(qOpt: GroupBy, rOpt: GroupBy, qSelection: Selection,
      rollupColIdx: Map[Expr, Int]): Option[GroupBy] = {
    (qOpt, rOpt) match {
      // If the query has no group by and the rollup has no group by then all is well
      case (None, None) => Some(None)
      // If the query and the rollup are grouping on the same columns (possibly in a different order) then
      // we can just leave the grouping off when querying the rollup to make it less expensive.
      case (Some(q), Some(r)) if q.toSet == r.toSet => Some(None)
      // If the query isn't grouped but the rollup is, everything in the selection must be an aggregate.
      // For example, a "SELECT sum(cost) where type='Boat'" could be satisfied by a rollup grouped by type.
      // We rely on the selection rewrite to ensure the columns are there, validate if it is self aggregatable, etc.
      case (None, Some(_)) if qSelection.forall {
        case (_, f: FunctionCall) if f.function.isAggregate => true
        case _ => false
      } => Some(None)
      // if the query is grouping, every grouping in the query must grouped in the rollup.
      // The analysis already validated there are no un-grouped columns in the selection
      // that aren't in the group by.
      case (Some(q), _) =>
        val grouped: Seq[Option[Expr]] = q.map { expr => rewriteExpr(expr, rollupColIdx) }

        if (grouped.forall(g => g.isDefined)) {
          Some(Some(grouped.flatten))
        } else {
          None
        }
      // TODO We can also rewrite if the rollup has no group bys, but need to do the right aggregate
      // manipulation.
      case _ => None
    }
  }

  /** Find the first column index that is a count(*) or count(literal). */
  def findCountStarOrLiteral(rollupColIdx: Map[Expr, Int]): Option[Int] = {
    rollupColIdx.find {
      case (fc: FunctionCall, _) => isCountStarOrLiteral(fc)
      case _ => false
    }.map(_._2)
  }

  /** Is the given function call a count(*) or count(literal), excluding NULL because it is special.  */
  def isCountStarOrLiteral(fc: FunctionCall): Boolean = {
    fc.function.function == CountStar ||
      (fc.function.function == Count &&
        fc.parameters.forall {
          case e: typed.NullLiteral[_] => false
          case e: typed.TypedLiteral[_] => true
          case _ => false
        })
  }

  /** Can this function be applied to its own output in a further aggregation */
  def isSelfAggregatableAggregate(f: Function[_]): Boolean = f match {
    case Max | Min | Sum => true
    case _ => false
  }

  /**
   * Looks at the rollup columns supplied and tries to find one of the supplied functions whose first parameter
   * operates on the given ColumnRef.  If there are multiple matches, returns the first matching function.
   * Every function supplied must take at least one parameter.
   */
  private def findFunctionOnColumn(rollupColIdx: Map[Expr, Int],
                                   functions: Seq[Function[_]],
                                   colRef: ColumnRef): Option[Int] = {
    val possibleColIdxs = functions.map { function =>
      rollupColIdx.find {
        case (fc: FunctionCall, _) if fc.function.function == function && fc.parameters.head == colRef => true
        case _ => false
      }.map(_._2)
    }
    possibleColIdxs.flatten.headOption
  }

  /** An in order hierarchy of floating timestamp date truncation functions, from least granular to most granular.  */
  private val dateTruncHierarchy = Seq(
    FloatingTimeStampTruncY,
    FloatingTimeStampTruncYm,
    FloatingTimeStampTruncYmd)

  /**
   * This tries to rewrite a between expression on floating timestamps to use an aggregated rollup column.
   * These functions have a hierarchy, so a query for a given level of truncation can be served by any other
   * truncation that is at least as long, eg. ymd can answer ym queries.
   *
   * This is slightly different than the more general expression rewrites because BETWEEN returns a boolean, so
   * there is no need to apply the date aggregation function on the ColumnRef.  In fact, we do not want to
   * apply the aggregation function on the ColumnRef because that will end up being bad for query execution
   * performance in most cases.
   *
   * @param fc A NOT BETWEEN or BETWEEN function call.
   */
  private def rewriteDateTruncBetweenExpr(rollupColIdx: Map[Expr, Int], fc: FunctionCall): Option[Expr] = {
    assert(fc.function.function == Between || fc.function.function == NotBetween)
    val maybeColRef +: lower +: upper +: _ = fc.parameters
    val commonTruncFunction = commonDateTrunc(lower, upper)

    /** The column index in the rollup that matches the common truncation function,
      * either exactly or at a more granular level */
    val colIdx = maybeColRef match {
      case colRef: ColumnRef if colRef.typ == SoQLFloatingTimestamp =>
        for {
        // we can rewrite to any date_trunc_xx that is the same or after the desired one in the hierarchy
          possibleTruncFunctions <- commonTruncFunction.map { tf => dateTruncHierarchy.dropWhile { f => f != tf } }
          idx <- findFunctionOnColumn(rollupColIdx, possibleTruncFunctions, colRef)
        } yield idx
      case _ => None
    }

    /** Now rewrite the BETWEEN to use the rollup, if possible. */
    colIdx match {
      case Some(idx: Int) =>
        // All we have to do is replace the first argument with the rollup column reference since it is just
        // being used as a comparison that result in a boolean, then rewrite the b and c expressions.
        // ie. 'foo_date BETWEEN date_trunc_y("2014/01/01") AND date_trunc_y("2019/05/05")'
        // just has to replace foo_date with rollup column "c<n>"
        for {
          lowerRewrite <- rewriteExpr(lower, rollupColIdx)
          upperRewrite <- rewriteExpr(upper, rollupColIdx)
          newParams <- Some(Seq(
            typed.ColumnRef(NoQualifier, rollupColumnId(idx), SoQLFloatingTimestamp.t)(fc.position),
            lowerRewrite,
            upperRewrite))
        } yield fc.copy(parameters = newParams)
      case _ => None
    }
  }

  /** The common date truncation function shared between the lower and upper bounds of the between */
  private def commonDateTrunc[T, U](lower: Expr,
                                    upper: Expr): Option[Function[SoQLType]] = {
    (lower, upper) match {
      case (lowerFc: FunctionCall, upperFc: FunctionCall) =>
        (lowerFc.function.function, upperFc.function.function) match {
          case (l, u) if l == u && dateTruncHierarchy.contains(l) => Some(l)
          case _ => None
        }
      case _ => None
    }
  }

  /**
   * Returns the least granular date truncation function that can be applied to the timestamp
   * without changing its value.
   */
  private[querycoordinator] def truncatedTo(soqlTs: SoQLFloatingTimestamp)
    : Option[Function[SoQLType]] = {
    val ts: LocalDateTime = soqlTs.value
    if (ts.getMillisOfDay != 0) {
      None
    } else if (ts.getDayOfMonth != 1) {
      Some(FloatingTimeStampTruncYmd)
    } else if (ts.getMonthOfYear != DateTimeConstants.JANUARY) {
      Some(FloatingTimeStampTruncYm)
    } else {
      Some(FloatingTimeStampTruncY)
    }
  }

  /**
   * Rewrite "less than" and "greater to or equal" to use rollup columns.  Note that date_trunc_xxx functions
   * are a form of a floor function.  This means that date_trunc_xxx(column) >= value will always be
   * the same as column >= value iff value could have been output by date_trunc_xxx.  Similar holds
   * for Lt, only it has to be strictly less since floor rounds down.
   *
   * For example, column >= '2014-03-01' AND column < '2015-05-01' can be rewritten as
   * date_trunc_ym(column) >= '2014-03-01' AND date_trunc_ym(column) < '2015-05-01' without changing
   * the results.
   *
   * Note that while we wouldn't need any of the logic here if queries explicitly came in as filtering
   * on date_trunc_xxx(column), we do not want to encourage that form of querying since is typically
   * much more expensive when you can't hit a rollup table.
   */
  private def rewriteDateTruncGteLt(rollupColIdx: Map[Expr, Int], fc: FunctionCall): Option[Expr] = {
    fc.function.function match {
      case Lt | Gte =>
        val left +: right +: _ = fc.parameters
        (left, right) match {
          // The left hand side should be a floating timestamp, and the right hand side will be a string being cast
          // to a floating timestamp.  eg. my_floating_timestamp < '2010-01-01'::floating_timestamp
          // While it is eminently reasonable to also accept them in flipped order, that is being left for later.
          case (colRef@typed.ColumnRef(_, _, SoQLFloatingTimestamp),
          cast@typed.FunctionCall(MonomorphicFunction(TextToFloatingTimestamp, _), Seq(typed.StringLiteral(ts, _)))) =>
            for {
              parsedTs <- SoQLFloatingTimestamp.StringRep.unapply(ts)
              truncatedTo <- truncatedTo(SoQLFloatingTimestamp(parsedTs))
              // we can rewrite to any date_trunc_xx that is the same or after the desired one in the hierarchy
              possibleTruncFunctions <- Some(dateTruncHierarchy.dropWhile { f => f != truncatedTo })
              rollupColIdx <- findFunctionOnColumn(rollupColIdx, possibleTruncFunctions, colRef)
              newParams <- Some(Seq(typed.ColumnRef(
                NoQualifier,
                rollupColumnId(rollupColIdx),
                SoQLFloatingTimestamp.t)(fc.position), right))
            } yield fc.copy(parameters = newParams)
          case _ => None
        }
      case _ => None
    }
  }

  /** Recursively maps the Expr based on the rollupColIdx map, returning either
    * a mapped expression or None if the expression couldn't be mapped.
    *
    * Note that every case here needs to ensure to map every expression recursively
    * to ensure it is either a literal or mapped to the rollup.
    */
  def rewriteExpr(e: Expr, rollupColIdx: Map[Expr, Int]): Option[Expr] = { // scalastyle:ignore cyclomatic.complexity
    log.trace("Attempting to match expr: {}", e)
    e match {
      case literal: typed.TypedLiteral[_] => Some(literal) // This is literally a literal, so so literal.
      // for a column reference we just need to map the column id
      case cr: ColumnRef => for {idx <- rollupColIdx.get(cr)} yield cr.copy(column = rollupColumnId(idx))
      // A count(*) or count(non-null-literal) on q gets mapped to a sum on any such column in rollup
      case fc: FunctionCall if isCountStarOrLiteral(fc) =>
        val mf = MonomorphicFunction(Sum, Map("a" -> SoQLNumber))
        for {
          idx <- findCountStarOrLiteral(rollupColIdx) // find count(*) column in rollup
          newSumCol <- Some(typed.ColumnRef(NoQualifier, rollupColumnId(idx), SoQLNumber.t)(fc.position))
          newFc <- Some(typed.FunctionCall(mf, Seq(newSumCol))(fc.position, fc.position))
        } yield newFc
      // A count(...) on q gets mapped to a sum(...) on a matching column in the rollup.  We still need the count(*)
      // case above to ensure we can do things like map count(1) and count(*) which aren't exact matches.
      case fc@typed.FunctionCall(MonomorphicFunction(Count, _), _) =>
        val mf = MonomorphicFunction(Sum, Map("a" -> SoQLNumber))
        for {
          idx <- rollupColIdx.get(fc) // find count(...) in rollup that matches exactly
          newSumCol <- Some(typed.ColumnRef(NoQualifier, rollupColumnId(idx), SoQLNumber.t)(fc.position))
          newFc <- Some(typed.FunctionCall(mf, Seq(newSumCol))(fc.position, fc.position))
        } yield newFc
      // If this is a between function operating on floating timestamps, and arguments b and c are both date aggregates,
      // then try to rewrite argument a to use a rollup.
      case fc: FunctionCall
        if (fc.function.function == Between || fc.function.function == NotBetween) &&
          fc.function.bindings.values.forall(_ == SoQLFloatingTimestamp) &&
          fc.function.bindings.values.tail.forall(dateTruncHierarchy contains _) =>
        rewriteDateTruncBetweenExpr(rollupColIdx, fc)
      // If it is a >= or < with floating timestamp arguments, see if we can rewrite to date_trunc_xxx
      case fc@typed.FunctionCall(MonomorphicFunction(fnType, bindings), _)
        if (fnType == Gte || fnType == Lt) &&
          bindings.values.forall(_ == SoQLFloatingTimestamp) =>
        rewriteDateTruncGteLt(rollupColIdx, fc)
      // Not null on a column can be translated to not null on a date_trunc_xxx(column)
      // There is actually a much more general case on this where non-aggregate functions can
      // be applied on top of other non-aggregate functions in many cases that we are not currently
      // implementing.
      case fc@typed.FunctionCall(MonomorphicFunction(IsNotNull, _), Seq(colRef@typed.ColumnRef(_, _, _)))
        if findFunctionOnColumn(rollupColIdx, dateTruncHierarchy, colRef).isDefined =>
        for {
          colIdx <- findFunctionOnColumn(rollupColIdx, dateTruncHierarchy, colRef)
        } yield fc.copy(
          parameters = Seq(typed.ColumnRef(NoQualifier, rollupColumnId(colIdx), colRef.typ)(fc.position)))
      case fc: FunctionCall if !fc.function.isAggregate => rewriteNonagg(rollupColIdx, fc)
      // If the function is "self aggregatable" we can apply it on top of an already aggregated rollup
      // column, eg. select foo, bar, max(x) max_x group by foo, bar --> select foo, max(max_x) group by foo
      // If we have a matching column, we just need to update its argument to reference the rollup column.
      case fc: FunctionCall if isSelfAggregatableAggregate(fc.function.function) =>
        for {idx <- rollupColIdx.get(fc)} yield fc.copy(
          parameters = Seq(typed.ColumnRef(NoQualifier, rollupColumnId(idx), fc.typ)(fc.position)))
      case _ => None
    }
  }

  // remaining non-aggregate functions
  private def rewriteNonagg(rollupColIdx: Map[Expr, Int],
                            fc: FunctionCall): Option[typed.CoreExpr[ColumnId, SoQLType] with Serializable] = {
    // if we have the exact same function in rollup and query, just turn it into a column ref in the rollup
    val functionMatch = for {
      idx <- rollupColIdx.get(fc)
    } yield typed.ColumnRef(NoQualifier, rollupColumnId(idx), fc.typ)(fc.position)
    // otherwise, see if we can recursively rewrite
    functionMatch.orElse {
      val mapped = fc.parameters.map(fe => rewriteExpr(fe, rollupColIdx))
      log.trace("mapped expr params {} {} -> {}", "", fc.parameters, mapped)
      if (mapped.forall(fe => fe.isDefined)) {
        log.trace("expr params all defined")
        Some(fc.copy(parameters = mapped.flatten))
      } else {
        None
      }
    }
  }

  def rewriteWhere(qeOpt: Option[Expr], r: Anal, rollupColIdx: Map[Expr, Int]): Option[Where] = {
    log.debug(s"Attempting to map query where expression '${qeOpt}' to rollup ${r}")

    (qeOpt, r.where) match {
      // don't support rollups with where clauses yet.  To do so, we need to validate that r.where
      // is also contained in q.where.
      case (_, Some(re)) => None
      // no where on query or rollup, so good!  No work to do.
      case (None, None) => Some(None)
      // have a where on query so try to map recursively
      case (Some(qe), None) => rewriteExpr(qe, rollupColIdx).map(Some(_))
    }
  }

  def rewriteHaving(qeOpt: Option[Expr], r: Anal, rollupColIdx: Map[Expr, Int]): Option[Having] = {
    log.debug(s"Attempting to map query having expression '${qeOpt}' to rollup ${r}")

    (qeOpt, r.having) match {
      // don't support rollups with having clauses yet.  To do so, we need to validate that r.having
      // is also contained in q.having.
      case (_, Some(re)) => None
      // no having on query or rollup, so good!  No work to do.
      case (None, None) => Some(None)
      // have a having on query so try to map recursively
      case (Some(qe), None) => rewriteExpr(qe, rollupColIdx).map(Some(_))
    }
  }

  def rewriteOrderBy(obsOpt: Option[Seq[OrderBy]], rollupColIdx: Map[Expr, Int]): Option[Option[Seq[OrderBy]]] = {
    log.debug(s"Attempting to map order by expression '${obsOpt}'") // scalastyle:ignore multiple.string.literals

    // it is silly if the rollup has an order by, but we really don't care.
    obsOpt match {
      case Some(obs) =>
        val mapped = obs.map { ob =>
          rewriteExpr(ob.expression, rollupColIdx) match {
            case Some(e) => Some(ob.copy(expression = e))
            case None => None
          }
        }
        if (mapped.forall { ob => ob.isDefined }) {
          Some(Some(mapped.flatten))
        } else {
          None
        }
      case None => Some(None)
    }
  }


  def possibleRewrites(schema: Schema, q: Anal, rollups: Seq[RollupInfo], project: Map[String, String]): Map[RollupName, Anal] = {
    possibleRewrites(q, analyzeRollups(schema, rollups, project))
  }

  def possibleRewrites(q: Anal, rollups: Map[RollupName, Anal]): Map[RollupName, Anal] = {
    log.debug("looking for candidates to rewrite for query: {}", q)
    val candidates = rollups.mapValues { r =>
      log.debug("checking for compat with: {}", r)

      // this lets us lookup the column and get the 0 based index in the select list
      val rollupColIdx = r.selection.values.zipWithIndex.toMap

      val groupBy = rewriteGroupBy(q.groupBy, r.groupBy, q.selection, rollupColIdx)
      val where = rewriteWhere(q.where, r, rollupColIdx)

      /*
       * As an optimization for query performance, the group rewrite code can turn a grouped query into an ungrouped
       * query if the grouping matches.  If it did that, we need to fix up the selection and ordering (and having if it
       * we supported it)  We need to ensure we don't remove aggregates from a query without any group bys to
       * start with, eg. "SELECT count(*)".  The rewriteGroupBy call above will indicate we can do this by returning
       * a Some(None) for the group bys.
       *
       * For example:
       * rollup: SELECT crime_type AS c1, count(*) AS c2, max(severity) AS c3 GROUP BY crime_type
       * query: SELECT crime_type, count(*), max(severity) GROUP BY crime_type
       * previous rollup query: SELECT c1, sum(c2), max(c3) GROUP BY c1
       * desired rollup query:  SELECT c1, c2, c3
       */
      val shouldRemoveAggregates = groupBy match {
        case Some(None) => q.groupBy.isDefined
        case _ => false
      }

      val selection = rewriteSelection(q.selection, r.selection, rollupColIdx) map { s =>
        if (shouldRemoveAggregates) s.mapValues(removeAggregates)
        else s
      }

      val orderBy = rewriteOrderBy(q.orderBy, rollupColIdx) map { o =>
        if (shouldRemoveAggregates) removeAggregates(o)
        else o
      }

      val having = rewriteHaving(q.having, r, rollupColIdx) map { h =>
        if (shouldRemoveAggregates) h.map(removeAggregates)
        else h
      }

      val mismatch =
        ensure(selection.isDefined, "mismatch on select") orElse
          ensure(where.isDefined, "mismatch on where") orElse
          ensure(groupBy.isDefined, "mismatch on groupBy") orElse
          ensure(having.isDefined, "mismatch on having") orElse
          ensure(orderBy.isDefined, "mismatch on orderBy") orElse
          // For limit and offset, we can always apply them from the query  as long as the rollup
          // doesn't have any.  For certain cases it would be possible to rewrite even if the rollup
          // has a limit or offset, but we currently don't.
          ensure(None == r.limit, "mismatch on limit") orElse
          ensure(None == r.offset, "mismatch on offset") orElse
          ensure(q.search == None, "mismatch on search") orElse
          ensure(q.distinct == r.distinct, "mismatch on distinct")

      mismatch match {
        case None =>
          Some(r.copy(
            isGrouped = if (shouldRemoveAggregates) groupBy.get.isDefined else q.isGrouped,
            selection = selection.get,
            groupBy = groupBy.get,
            orderBy = orderBy.get,
            // If we are removing aggregates then we are no longer grouping and need to put the condition
            // in the where instead of the having.
            where = if (shouldRemoveAggregates) andExpr(where.get, having.get) else where.get,
            having = if (shouldRemoveAggregates) None else having.get,
            limit = q.limit,
            offset = q.offset
          ))
        case Some(s) =>
          log.debug("Not compatible: {}", s)
          None
      }
    }

    log.debug("Final candidates: {}", candidates)
    candidates.collect { case (k, Some(v)) => k -> v }
  }

  def andExpr(a: Option[Expr], b: Option[Expr]): Option[Expr] = {
    (a, b) match {
      case (None, None) => None
      case (Some(a), None) => Some(a)
      case (None, Some(b)) => Some(b)
      case (Some(a), Some(b)) => Some(typed.FunctionCall(SoQLFunctions.And.monomorphic.get, List(a, b))(NoPosition, NoPosition))
    }
  }


  /**
   * For analyzing the rollup query, we need to map the dataset schema column ids to the "_" prefixed
   * version of the name that we get, designed to ensure the column name is valid soql
   * and doesn't start with a number.
   */
  private def addRollupPrefix(name: ColumnId): ColumnName = {
    new ColumnName(if (name(0) == ':') name else "_" + name)
  }


  /**
   * Once we have the analyzed rollup query with column names, we need to remove the leading "_" on non-system
   * columns to make the names match up with the underlying dataset schema.
   */
  private def removeRollupPrefix(cn: ColumnName, qual: Qualifier): ColumnId = { // TODO: Join
    cn.name(0) match {
      case ':' => cn.name
      case _ => cn.name.drop(1)
    }
  }

  // maps prefixed column name to type
  private def prefixedDsContext(schema: Schema) = {
    val columnIdMap = schema.schema.map { case (k, v) => addRollupPrefix(k) -> k }
    Map(TableName.PrimaryTable.qualifier -> QueryParser.dsContext(columnIdMap, schema.schema))
  }

  def analyzeRollups(schema: Schema, rollups: Seq[RollupInfo], project: Map[String, String]): Map[RollupName, Anal] = {

    // TODO: Join - qualifier
    def reprojectColumn(cn: ColumnId, qual: Qualifier): ColumnId = project.getOrElse(cn, cn)

    val dsContext = prefixedDsContext(schema)
    val rollupMap = rollups.map { r => (r.name, r.soql) }.toMap
    val analysisMap = rollupMap.mapValues { soql =>
      Try(analyzer.analyzeUnchainedQuery(soql)(dsContext).mapColumnIds(removeRollupPrefix).mapColumnIds(reprojectColumn))
    }

    analysisMap.foreach {
      case (rollupName, Failure(e: SoQLException)) => log.info(s"Couldn't parse rollup $rollupName, ignoring: ${e.toString}")
      case (rollupName, Failure(e)) => log.warn(s"Couldn't parse rollup $rollupName due to unexpected failure, ignoring", e)
      case _ =>
    }

    analysisMap collect {
      case (k, Success(a)) =>
        val ruAnalysis =
          if (project.nonEmpty) {
            val reprojectedSelection = a.selection.map {
              case x@(cn: ColumnName, expr) =>
                project.get(cn.name.stripMargin('_')) match {
                  case Some(newName) =>
                    (new ColumnName(newName), expr)
                  case None =>
                    x
                }
            }
            a.copy(selection = reprojectedSelection)
          } else {
            a
          }
        k -> ruAnalysis
    }
  }
}


object QueryRewriter {
  import com.socrata.soql.typed._ // scalastyle:ignore import.grouping

  type Anal = SoQLAnalysis[ColumnId, SoQLType]
  type ColumnId = String
  type ColumnRef = typed.ColumnRef[ColumnId, SoQLType]
  type Expr = CoreExpr[ColumnId, SoQLType]
  type FunctionCall = typed.FunctionCall[ColumnId, SoQLType]
  type GroupBy = Option[Seq[Expr]]
  type OrderBy = typed.OrderBy[ColumnId, SoQLType]
  type RollupName = String
  type Selection = OrderedMap[ColumnName, Expr]
  type Where = Option[Expr]
  type Having = Option[Expr]
}
