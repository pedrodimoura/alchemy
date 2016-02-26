package rxsqlite.compiler;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

import rx.Observable;
import rxsqlite.annotation.SQLiteColumn;
import rxsqlite.annotation.SQLiteObject;
import rxsqlite.annotation.SQLitePk;

/**
 * @author Daniel Serdyukov
 */
class TableMaker {

    static final String PACKAGE_NAME = "rxsqlite";

    static final ClassName RXS_TABLE = ClassName.get(PACKAGE_NAME, "RxSQLiteTable");

    static final ClassName RXS_TYPES = ClassName.get(PACKAGE_NAME, "Types");

    static final ClassName SQLITE_DB = ClassName.get("sqlite4a", "SQLiteDb");

    static final ClassName SQLITE_STMT = ClassName.get("sqlite4a", "SQLiteStmt");

    static final ClassName SQLITE_ROW = ClassName.get("sqlite4a", "SQLiteRow");

    static final ClassName SQLITE_ROW_SET = ClassName.get("sqlite4a", "SQLiteRowSet");

    static final ClassName OBSERVABLE = ClassName.get(Observable.class);

    private final ClassName mModelClass;

    private final Deque<String> mDefinitions = new LinkedList<>();

    private final Map<String, TypeMirror> mColumnTypes = new LinkedHashMap<>();

    private final Map<String, String> mColumnNames = new LinkedHashMap<>();

    private String mTableName;

    private boolean mHasPrimaryKey;

    TableMaker(TypeElement element) {
        mModelClass = ClassName.get(element);
    }

    void parseSQLiteObject(Element element) throws Exception {
        final SQLiteObject annotation = element.getAnnotation(SQLiteObject.class);
        mTableName = annotation.value();
        mDefinitions.add("\", " + Utils.join(", ", annotation.constraints()) + "\"");
    }

    void parseSQLitePk(Element element) throws Exception {
        final TypeMirror type = element.asType();
        final TypeKind kind = type.getKind();
        if (TypeKind.LONG != kind) {
            throw new IllegalArgumentException("Primary key must be 'long'");
        }
        Utils.setAccessible(element);
        final SQLitePk annotation = element.getAnnotation(SQLitePk.class);
        String constraint = annotation.constraint();
        if (!Utils.isEmpty(constraint)) {
            constraint = " " + constraint;
        }
        mDefinitions.add("\"_id INTEGER PRIMARY KEY" + constraint + "\"");
        mColumnTypes.put(element.getSimpleName().toString(), type);
        mColumnNames.put("_id", element.getSimpleName().toString());
        mHasPrimaryKey = true;
    }

    void parseSQLiteColumn(Element element) throws Exception {
        Utils.setAccessible(element);
        final SQLiteColumn annotation = element.getAnnotation(SQLiteColumn.class);
        final String fieldName = element.getSimpleName().toString();
        final TypeMirror type = element.asType();
        String columnName = annotation.value();
        if (Utils.isEmpty(columnName)) {
            columnName = Utils.getColumnName(fieldName);
        }
        String constraint = annotation.constraint();
        if (!Utils.isEmpty(constraint)) {
            constraint = " " + constraint;
        }
        if (Utils.isLongType(type)) {
            mDefinitions.add("\", " + columnName + " INTEGER" + constraint + "\"");
        } else if (Utils.isDoubleType(type)) {
            mDefinitions.add("\", " + columnName + " REAL" + constraint + "\"");
        } else if (Utils.isStringType(type)) {
            mDefinitions.add("\", " + columnName + " TEXT" + constraint + "\"");
        } else if (Utils.isBlobType(type)) {
            mDefinitions.add("\", " + columnName + " BLOB" + constraint + "\"");
        } else if (Utils.isEnumType(type)) {
            mDefinitions.add("\", " + columnName + " TEXT" + constraint + "\"");
        } else {
            mDefinitions.add("\", " + columnName + " \" + mTypes.getType(" + type + ".class) + \"" + constraint + "\"");
        }
        mColumnTypes.put(element.getSimpleName().toString(), type);
        mColumnNames.put(columnName, fieldName);
    }

    JavaFile brewTableJava() throws Exception {
        if (Utils.isEmpty(mTableName)) {
            throw new IllegalArgumentException("not annotated with @" + SQLiteObject.class.getCanonicalName());
        }
        if (!mHasPrimaryKey) {
            throw new IllegalArgumentException("has no field annotated with @" + SQLitePk.class.getCanonicalName());
        }
        final TypeSpec.Builder spec = TypeSpec.classBuilder(mModelClass.simpleName() + "$$Table")
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(ParameterizedTypeName.get(RXS_TABLE, mModelClass))
                .addField(CustomTypesMaker.className(), "mTypes", Modifier.PRIVATE, Modifier.FINAL)
                .addMethod(MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(CustomTypesMaker.className(), "types")
                        .addStatement("mTypes = types")
                        .build());
        brewCreateMethod(spec);
        brewQueryMethod(spec);
        brewSaveMethod(spec);
        brewRemoveMethod(spec);
        brewClearMethod(spec);
        brewInstantiateMethod(spec);
        brewBindValuesMethod(spec);
        return JavaFile.builder(mModelClass.packageName(), spec.build())
                .addFileComment("Generated code from RxSQLite. Do not modify!")
                .skipJavaLangImports(true)
                .build();
    }

    private void brewCreateMethod(TypeSpec.Builder typeSpec) throws Exception {
        final String constraints = mDefinitions.removeFirst();
        if (!"\", \"".equals(constraints)) {
            mDefinitions.addLast(constraints);
        }
        final CodeBlock.Builder query = CodeBlock.builder();
        query.add("db.exec(\"CREATE TABLE IF NOT EXISTS $L(\"", mTableName);
        query.indent();
        for (final String definition : mDefinitions) {
            query.add("\n + $L", definition);
        }
        query.add("\n + \");\", null);\n");
        typeSpec.addMethod(MethodSpec.methodBuilder("create")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(SQLITE_DB, "db")
                .addCode(query.build())
                .build());
    }

    private void brewQueryMethod(TypeSpec.Builder typeSpec) throws Exception {
        typeSpec.addMethod(MethodSpec.methodBuilder("query")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(SQLITE_DB, "db")
                .addParameter(String.class, "selection")
                .addParameter(ParameterizedTypeName.get(ClassName.get(Iterable.class),
                        ClassName.get(Object.class)), "bindValues")
                .returns(ParameterizedTypeName.get(OBSERVABLE, mModelClass))
                .addStatement("final $T stmt = db.prepare(\"SELECT * FROM $L\" + selection)", SQLITE_STMT, mTableName)
                .beginControlFlow("try")
                .addStatement("final $T objects = new $T<>()", ParameterizedTypeName
                        .get(ClassName.get(List.class), mModelClass), ClassName.get(ArrayList.class))
                .addStatement("int index = 0")
                .beginControlFlow("for (final Object value : bindValues)")
                .addStatement("mTypes.bindValue(stmt, ++index, value)")
                .endControlFlow()
                .addStatement("final $T rows = stmt.executeSelect()", SQLITE_ROW_SET)
                .beginControlFlow("while (rows.step())")
                .addStatement("objects.add(instantiate(rows))")
                .endControlFlow()
                .addStatement("return $T.from(objects)", OBSERVABLE)
                .nextControlFlow("finally")
                .addStatement("stmt.close()")
                .endControlFlow()
                .build());
    }

    private void brewSaveMethod(TypeSpec.Builder typeSpec) throws Exception {
        final Set<String> columns = mColumnNames.keySet();
        final List<String> binding = Collections.nCopies(columns.size(), "?");
        typeSpec.addMethod(MethodSpec.methodBuilder("save")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(SQLITE_DB, "db")
                .addParameter(ParameterizedTypeName.get(ClassName.get(Iterable.class), mModelClass), "objects")
                .returns(ParameterizedTypeName.get(OBSERVABLE, mModelClass))
                .addStatement("final $T stmt = db.prepare(\"INSERT INTO $L($L) VALUES($L);\")",
                        SQLITE_STMT, mTableName, Utils.join(", ", columns), Utils.join(", ", binding))
                .beginControlFlow("try")
                .beginControlFlow("for (final $T object : objects)", mModelClass)
                .addStatement("stmt.clearBindings()")
                .addStatement("bindStmtValues(stmt, object)")
                .addStatement("stmt.execute()")
                .addStatement("object.$L = db.getLastInsertRowId()", mColumnNames.values().iterator().next())
                .endControlFlow()
                .addStatement("return $T.from(objects)", OBSERVABLE)
                .nextControlFlow("finally")
                .addStatement("stmt.close()")
                .endControlFlow()
                .build());
    }

    private void brewRemoveMethod(TypeSpec.Builder typeSpec) throws Exception {
        typeSpec.addMethod(MethodSpec.methodBuilder("remove")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(SQLITE_DB, "db")
                .addParameter(ParameterizedTypeName.get(ClassName.get(Iterable.class), mModelClass), "objects")
                .returns(ParameterizedTypeName.get(OBSERVABLE, ClassName.get(Integer.class)))
                .addStatement("final $T stmt = db.prepare(\"DELETE FROM $L WHERE _id = ?;\")",
                        SQLITE_STMT, mTableName)
                .beginControlFlow("try")
                .addStatement("int affectedRows = 0")
                .beginControlFlow("for (final $T object : objects)", mModelClass)
                .addStatement("stmt.clearBindings()")
                .addStatement("stmt.bindLong(1, object.$L)", mColumnNames.values().iterator().next())
                .addStatement("affectedRows += stmt.execute()")
                .endControlFlow()
                .addStatement("return $T.just(affectedRows)", OBSERVABLE)
                .nextControlFlow("finally")
                .addStatement("stmt.close()")
                .endControlFlow()
                .build());
    }

    private void brewClearMethod(TypeSpec.Builder typeSpec) throws Exception {
        typeSpec.addMethod(MethodSpec.methodBuilder("clear")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(SQLITE_DB, "db")
                .addParameter(String.class, "selection")
                .addParameter(ParameterizedTypeName.get(ClassName.get(Iterable.class),
                        ClassName.get(Object.class)), "bindValues")
                .returns(ParameterizedTypeName.get(OBSERVABLE, ClassName.get(Integer.class)))
                .addStatement("final $T stmt = db.prepare(\"DELETE FROM $L\" + selection)", SQLITE_STMT, mTableName)
                .beginControlFlow("try")
                .addStatement("int index = 0")
                .beginControlFlow("for (final Object value : bindValues)")
                .addStatement("mTypes.bindValue(stmt, ++index, value)")
                .endControlFlow()
                .addStatement("return $T.just(stmt.execute())", OBSERVABLE)
                .nextControlFlow("finally")
                .addStatement("stmt.close()")
                .endControlFlow()
                .build());
    }

    private void brewInstantiateMethod(TypeSpec.Builder typeSpec) throws Exception {
        final MethodSpec.Builder methodSpec = MethodSpec.methodBuilder("instantiate")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(TableMaker.SQLITE_ROW, "row")
                .returns(mModelClass);
        methodSpec.addStatement("final $1T object = new $1T()", mModelClass);
        int index = 0;
        for (final Map.Entry<String, TypeMirror> entry : mColumnTypes.entrySet()) {
            final String fieldName = entry.getKey();
            final TypeMirror type = entry.getValue();
            if (Utils.isLongType(type)) {
                methodSpec.addStatement("object.$L = ($L) row.getColumnLong($L)", fieldName, type, index);
            } else if (Utils.isDoubleType(type)) {
                methodSpec.addStatement("object.$L = ($L) row.getColumnDouble($L)", fieldName, type, index);
            } else if (Utils.isStringType(type)) {
                methodSpec.addStatement("object.$L = row.getColumnString($L)", fieldName, index);
            } else if (Utils.isBlobType(type)) {
                methodSpec.addStatement("object.$L = row.getColumnBlob($L)", fieldName, index);
            } else if (Utils.isEnumType(type)) {
                methodSpec.addStatement("object.$L = mTypes.getEnumValue(row, $L, $L.class)", fieldName, index, type);
            } else {
                methodSpec.addStatement("object.$L = mTypes.getValue(row, $L, $L.class)", fieldName, index, type);
            }
            ++index;
        }
        methodSpec.addStatement("return object");
        typeSpec.addMethod(methodSpec.build());
    }

    private void brewBindValuesMethod(TypeSpec.Builder typeSpec) throws Exception {
        final MethodSpec.Builder methodSpec = MethodSpec.methodBuilder("bindStmtValues")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(SQLITE_STMT, "stmt")
                .addParameter(mModelClass, "object");
        final Iterator<Map.Entry<String, String>> iterator = mColumnNames.entrySet().iterator();
        int index = 1;
        if (iterator.hasNext()) {
            final Map.Entry<String, String> primaryKey = iterator.next();
            final String primaryKeyField = primaryKey.getValue();
            methodSpec.beginControlFlow("if (object.$L > 0)", primaryKeyField);
            methodSpec.addStatement("stmt.bindLong($L, object.$L)", index, primaryKeyField);
            methodSpec.nextControlFlow("else");
            methodSpec.addStatement("stmt.bindNull($L)", index);
            methodSpec.endControlFlow();
        }
        while (iterator.hasNext()) {
            ++index;
            final Map.Entry<String, String> column = iterator.next();
            final String fieldName = column.getValue();
            final TypeMirror type = mColumnTypes.get(fieldName);
            if (Utils.isLongType(type)) {
                methodSpec.addStatement("stmt.bindLong($L, object.$L)", index, fieldName);
            } else if (Utils.isDoubleType(type)) {
                methodSpec.addStatement("stmt.bindDouble($L, object.$L)", index, fieldName);
            } else if (Utils.isStringType(type)) {
                methodSpec.addStatement("stmt.bindString($L, object.$L)", index, fieldName);
            } else if (Utils.isBlobType(type)) {
                methodSpec.addStatement("stmt.bindBlob($L, object.$L)", index, fieldName);
            } else if (Utils.isEnumType(type)) {
                methodSpec.beginControlFlow("if (object.$L != null)", fieldName);
                methodSpec.addStatement("stmt.bindString($L, object.$L.name())", index, fieldName);
                methodSpec.nextControlFlow("else");
                methodSpec.addStatement("stmt.bindNull($L)", index);
                methodSpec.endControlFlow();
            } else {
                methodSpec.addStatement("mTypes.bindValue(stmt, $L, object.$L)", index, fieldName);
            }
        }
        typeSpec.addMethod(methodSpec.build());
    }

}
