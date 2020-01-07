未完整，会不断补充……


网络和协议
============================================================

使用一个连接对应一个线程的模型，最核心的线程有两类:
Thread [HSQLDB Server]和Thread[HSQLDB Connection]
Thread [HSQLDB Server]只有一个，而Thread[HSQLDB Connection]会依据连接数动态创建，连接关闭就结束。


- Thread [HSQLDB Server](Server端监听线程): 

执行org.hsqldb.server.Server.run方法，
在一个while循环中执行java.net.ServerSocket.accept。

- Thread[HSQLDB Connection]: 

监听线程在ServerSocket.accept那里接收到一个新的客户端连接后执行org.hsqldb.server.Server.handleConnection(Socket)，
然后创建一个org.hsqldb.server.ServerConnection实例，ServerConnection类实现了java.lang.Runnable接口，
每个ServerConnection实例对应一个Thread[HSQLDB Connection]，可以在ServerConnection.run方法中打断点方便调试。

Thread[HSQLDB Connection]在执行run方法时，会事先执行ServerConnection.init()

在执行ServerConnection.init()时做了很多工作:
1. 跟客户端握手
2. 看看客户端要访问哪个数据库
3. 验证客户端提供的用户名和密码是否正确: org.hsqldb.rights.UserManager.getUser(String, String)
4. 创建一个Session对象，创建Session对象时还要创建一个Scanner和ParserCommand，
   在同一Session中的所有SQL语句都会用同一个Scanner和ParserCommand进行词法和语法解析，
   这跟H2数据库不一样，H2是每条SQL都会新建一个Parser。


SQL解析与执行
============================================================
SQL解析与执行的工作都由Thread[HSQLDB Connection]负责

- SQL解析

在这一步会得到一个org.hsqldb.Statement子类的实例

Thread [HSQLDB Connection @79fb12] (Suspended)	
	owns: Session  (id=249)	
	ParserCommand.compilePart(int) line: 150	
	ParserCommand.compileStatements(String, Result) line: 95	
	Session.executeDirectStatement(Result) line: 1235	
	Session.execute(Result) line: 1031	
	ServerConnection.receiveResult(int) line: 380	
	ServerConnection.access$2(ServerConnection, int) line: 336	
	ServerConnection$HsqlInResultProcessor.receiveResult(int) line: 2032	
	ServerConnection.run() line: 1522	
	Thread.run() line: 745	



- SQL执行

在这一步执行org.hsqldb.Statement.execute，子类都实现了Statement.execute这个抽象方法

可以依据以下调用栈打断点:
Thread [HSQLDB Connection @79fb12] (Suspended (breakpoint at line 141 in StatementInsert))	
	owns: Session  (id=249)	
	StatementInsert.getResult(Session) line: 141	
	StatementInsert(StatementDMQL).execute(Session) line: 172	
	Session.executeCompiledStatement(Statement, Object[], int) line: 1412	
	Session.executeDirectStatement(Result) line: 1266	
	Session.execute(Result) line: 1031	
	ServerConnection.receiveResult(int) line: 380	
	ServerConnection.access$2(ServerConnection, int) line: 336	
	ServerConnection$HsqlInResultProcessor.receiveResult(int) line: 2032	
	ServerConnection.run() line: 1522	
	Thread.run() line: 745	


- Insert语句的执行

每条insert语句在解析完成后对应一个org.hsqldb.StatementInsert实例，
会依据表的类型关联上一个PersistentStore，如果最初用CREATE TABLE语句创建表时不加任何关键字就默认是内存表，
内存表对应org.hsqldb.persist.RowStoreAVLMemory，
用CREATE CACHED TABLE语句创建的表对应org.hsqldb.persist.RowStoreAVLDisk。
其他的对应关系见org.hsqldb.persist.Logger.newStore(Session, PersistentStoreCollection, TableBase)

可以依据以下调用栈打断点:
Thread [HSQLDB Connection @963c26] (Suspended)	
	owns: Session  (id=433)	
	IndexAVL.insert(Session, PersistentStore, Row) line: 812	
	RowStoreAVLDisk(RowStoreAVL).indexRow(Session, Row) line: 245	
	RowStoreAVLDisk.indexRow(Session, Row) line: 227	
	TransactionManagerMV2PL.addInsertAction(Session, Table, PersistentStore, Row, int[]) line: 311	
	Session.addInsertAction(Table, PersistentStore, Row, int[]) line: 472	
	Table.insertSingleRow(Session, PersistentStore, Object[], int[]) line: 2858	
	StatementInsert(StatementDML).insertSingleRow(Session, PersistentStore, Object[]) line: 952	
	StatementInsert.getResult(Session) line: 155	
	StatementInsert(StatementDMQL).execute(Session) line: 172	
	Session.executeCompiledStatement(Statement, Object[], int) line: 1412	
	Session.executeDirectStatement(Result) line: 1266	
	Session.execute(Result) line: 1031	
	ServerConnection.receiveResult(int) line: 380	
	ServerConnection.access$2(ServerConnection, int) line: 336	
	ServerConnection$HsqlInResultProcessor.receiveResult(int) line: 2032	
	ServerConnection.run() line: 1522	
	Thread.run() line: 745	


事务
============================================================
支持三种并发控制模型: 2PL、MV2PL、MVCC
可以使用以下的SET语句动态修改:
SET DATABASE TRANSACTION CONTROL { LOCKS | MVLOCKS | MVCC }

对应org.hsqldb.TransactionManager接口的三个实现类:

org.hsqldb.TransactionManager2PL
org.hsqldb.TransactionManagerMV2PL
org.hsqldb.TransactionManagerMVCC

