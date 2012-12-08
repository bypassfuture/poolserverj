To install as a windows service open service.bat in a text editor.
Make sure J_HOME is pointing to your Java JDK directory.
MIN_HEAP and MAX_HEAP are the min and max heap memory (in MB) allocated to the java virtual machine.  

e.g.
set J_HOME=C:\java\jdk\jdk1.6.0_18
set MIN_HEAP=16
set MAX_HEAP=128

to install the windows service make sure you are in the same directory as the executables:
cd <poolserverj-home-dir>/bin
win-service install ../conf/yourpropertiesfile.properties

if you do not specify a properties file PoolServerJ will look for the default properties file in the current directory: "PoolServerJ.properties"

to remove the service:
cd <poolserverj-home-dir>/bin
win-service remove
