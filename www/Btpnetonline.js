var exec = require('cordova/exec');

var BTPrinter = {
   list: function(fnSuccess, fnError){
      exec(fnSuccess, fnError, "Btpnetonline", "list", []);
   },
   connect: function(fnSuccess, fnError, name){
      exec(fnSuccess, fnError, "Btpnetonline", "connect", [name]);
   },
   disconnect: function(fnSuccess, fnError){
      exec(fnSuccess, fnError, "Btpnetonline", "disconnect", []);
   },
   print: function(fnSuccess, fnError, str){
      exec(fnSuccess, fnError, "Btpnetonline", "print", [str]);
   },
   printText: function(fnSuccess, fnError, str){
      exec(fnSuccess, fnError, "Btpnetonline", "printText", [str]);
   },
    printImage: function(fnSuccess, fnError, str){
      exec(fnSuccess, fnError, "Btpnetonline", "printImage", [str]);
    },
   printPOSCommand: function(fnSuccess, fnError, str){
      exec(fnSuccess, fnError, "Btpnetonline", "printPOSCommand", [str]);
   },
   gunes: function(fnSuccess, fnError){
      exec(fnSucess, fnError, "Btpnetonline", "gunes", []);
   }
};

module.exports = BTPrinter;
