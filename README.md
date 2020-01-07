### 项目用途

* [HSQLDB数据库](http://www.hsqldb.org/) 源代码学习研究(包括代码注释、文档、用于代码分析的测试用例)


### 目录结构

* my-docs: 综合文档

* my-test: 用于代码分析的测试用例

* src: HSQLDB数据库的最新源代码，在源代码中附加了便于分析理解代码的注释


### 把代码导入Eclipse

* 运行 mvn eclipse:eclipse 生成Eclipse项目，打开Eclipse，选择File -> Import -> Existing Projects into Workspace


### 运行或调试HSQLDB

* 右击 /hsqldb-research/my-test/java/my/test/HsqldbServerStart.java 文件，点Run As或Debug As -> Java Application

* 如果出现"Startup sequence completed"这样的提示就说明启动成功啦


### 测试

* my.test 包中的类几乎都可直接运行
