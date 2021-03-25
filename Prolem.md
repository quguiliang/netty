# 搭建过程中出现的问题
## 1、解决依赖报错
###    问题：
####    在codec-redis模块中，类FixedRedisMessagePool会报如下类不存在的问题：
#####        import io.netty.util.collection.LongObjectHashMap;
#####        import io.netty.util.collection.LongObjectMap;
###    解决方案：
####    1）cd common && mvn clean install
####    2）刷新工程，并reimport maven project

